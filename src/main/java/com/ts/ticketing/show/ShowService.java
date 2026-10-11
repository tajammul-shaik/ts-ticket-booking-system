package com.ts.ticketing.show;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.ts.ticketing.api.ApiException;
import com.ts.ticketing.observability.SeatGauges;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class ShowService {

    static final int MAX_SEATS = 10_000;
    static final long MAX_PRICE_PAISE = 10_000_000_000L;   // ₹10 crore; n × price stays far from overflow
    static final int MAX_PER_USER_LIMIT = 100;
    static final int DEFAULT_PER_USER_LIMIT = 4;           // BR-03

    private static final Logger log = LoggerFactory.getLogger(ShowService.class);

    private final ShowRepository shows;
    private final TransactionTemplate tx;
    private final SeatGauges gauges;
    /**
     * Show definitions are immutable once created, so caching them is safe and saves the
     * reserve hot path two queries. Seat *status* is never cached: every decision on it is SQL.
     */
    private final Map<String, Show> definitions = new ConcurrentHashMap<>();

    public ShowService(ShowRepository shows, TransactionTemplate tx, SeatGauges gauges) {
        this.shows = shows;
        this.tx = tx;
        this.gauges = gauges;
    }

    /** FR-02: the show and all its seats in one transaction. */
    public ShowView create(CreateShowRequest req) {
        List<String> labels = SeatLabels.sortedUnique(req.seats());
        int limit = req.perUserLimit() == null ? DEFAULT_PER_USER_LIMIT : req.perUserLimit();
        String id = UUID.randomUUID().toString();

        tx.executeWithoutResult(status -> {
            shows.insertShow(id, req.name().strip(), req.pricePaise(), limit, labels.size());
            shows.insertSeats(id, labels);
        });
        gauges.track(id);
        log.info("show created {} {} {}", kv("show_id", id), kv("total_seats", labels.size()), kv("per_user_limit", limit));
        return view(definition(id));
    }

    /** FR-05: current state of every seat. */
    public ShowView get(String id) {
        return view(definition(id));
    }

    /** The immutable definition, or 404 show_not_found. */
    public Show definition(String id) {
        Show cached = definitions.get(id);
        if (cached != null) {
            return cached;
        }
        Show loaded = shows.findShow(id)
                .orElseThrow(() -> ApiException.notFound("show_not_found", "no show with id " + id));
        definitions.putIfAbsent(id, loaded);
        return loaded;
    }

    private ShowView view(Show show) {
        List<ShowView.SeatState> seats = shows.seatStates(show.id());
        long available = 0;
        long held = 0;
        long confirmed = 0;
        for (ShowView.SeatState seat : seats) {
            switch (seat.status()) {
                case "available" -> available++;
                case "held" -> held++;
                case "confirmed" -> confirmed++;
                default -> throw new IllegalStateException("unknown seat status " + seat.status());
            }
        }
        boolean invariantOk = available + held + confirmed == show.totalSeats();   // BR-12
        if (!invariantOk) {
            log.error("reconciliation invariant broken {} {} {} {} {}", kv("show_id", show.id()),
                    kv("available", available), kv("held", held), kv("confirmed", confirmed),
                    kv("total_seats", show.totalSeats()));
        }
        return new ShowView(show.id(), show.name(), show.pricePaise(), show.perUserLimit(), show.totalSeats(),
                new ShowView.Counts(available, held, confirmed), invariantOk, seats);
    }
}
