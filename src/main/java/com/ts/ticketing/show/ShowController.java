package com.ts.ticketing.show;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-02 (admin only, enforced in SecurityConfig) and FR-05. */
@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService shows;

    public ShowController(ShowService shows) {
        this.shows = shows;
    }

    @PostMapping
    public ResponseEntity<ShowView> create(@Valid @RequestBody CreateShowRequest request) {
        ShowView show = shows.create(request);
        return ResponseEntity.created(URI.create("/shows/" + show.id())).body(show);
    }

    @GetMapping("/{id}")
    public ShowView get(@PathVariable String id) {
        return shows.get(id);
    }
}
