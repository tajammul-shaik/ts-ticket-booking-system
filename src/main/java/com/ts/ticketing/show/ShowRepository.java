package com.ts.ticketing.show;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Explicit SQL for shows and their seat inventory (NFR-14). */
@Repository
public class ShowRepository {

    private static final int INSERT_CHUNK = 1000;

    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertShow(String id, String name, long pricePaise, int perUserLimit, int totalSeats) {
        jdbc.update("INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats) VALUES (?, ?, ?, ?, ?)",
                id, name, pricePaise, perUserLimit, totalSeats);
    }

    /** Multi-row INSERTs of up to 1000 seats each, all 'available'. */
    public void insertSeats(String showId, List<String> labels) {
        for (int from = 0; from < labels.size(); from += INSERT_CHUNK) {
            List<String> chunk = labels.subList(from, Math.min(from + INSERT_CHUNK, labels.size()));
            String values = String.join(", ", Collections.nCopies(chunk.size(), "(?, ?, 'available')"));
            Object[] args = new Object[chunk.size() * 2];
            for (int i = 0; i < chunk.size(); i++) {
                args[2 * i] = showId;
                args[2 * i + 1] = chunk.get(i);
            }
            jdbc.update("INSERT INTO seats (show_id, seat_label, status) VALUES " + values, args);
        }
    }

    public Optional<Show> findShow(String id) {
        List<Show> rows = jdbc.query(
                "SELECT id, name, price_paise, per_user_limit, total_seats FROM shows WHERE id = ?",
                (rs, n) -> new Show(rs.getString("id"), rs.getString("name"), rs.getLong("price_paise"),
                        rs.getInt("per_user_limit"), rs.getInt("total_seats"), Set.of()),
                id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Show show = rows.getFirst();
        var labels = new HashSet<>(jdbc.queryForList("SELECT seat_label FROM seats WHERE show_id = ?", String.class, id));
        return Optional.of(new Show(show.id(), show.name(), show.pricePaise(), show.perUserLimit(),
                show.totalSeats(), Set.copyOf(labels)));
    }

    /**
     * Every seat with its status, from ONE statement: the list and the counts derived from it
     * are a single consistent snapshot (FR-05).
     */
    public List<ShowView.SeatState> seatStates(String showId) {
        return new ArrayList<>(jdbc.query(
                "SELECT seat_label, status FROM seats WHERE show_id = ? ORDER BY seat_label",
                (rs, n) -> new ShowView.SeatState(rs.getString("seat_label"), rs.getString("status")),
                showId));
    }
}
