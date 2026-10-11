-- ts-ticketing-system schema (BRD section 10). The constraints here, not Java code, are what
-- make a double-sell, a duplicate reservation or a negative balance impossible.
--
-- utf8mb4_bin: seat labels, user ids and idempotency keys compare byte-for-byte, so "a1" and
-- "A1" are different seats and lock order (ORDER BY seat_label) is the same everywhere.

CREATE TABLE shows (
    id             CHAR(36)     NOT NULL,
    name           VARCHAR(100) NOT NULL,
    price_paise    BIGINT       NOT NULL,
    per_user_limit INT          NOT NULL,
    total_seats    INT          NOT NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT chk_shows_price CHECK (price_paise >= 0),                -- BR-13
    CONSTRAINT chk_shows_limit CHECK (per_user_limit >= 1),
    CONSTRAINT chk_shows_total CHECK (total_seats >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- One row per seat, one status column: each seat is in exactly one state, so
-- available + held + confirmed = total_seats holds structurally (BR-12).
CREATE TABLE seats (
    show_id        CHAR(36)    NOT NULL,
    seat_label     VARCHAR(8)  NOT NULL,
    status         ENUM ('available', 'held', 'confirmed') NOT NULL DEFAULT 'available',
    holder_user_id VARCHAR(64) NULL,
    reservation_id CHAR(36)    NULL,
    held_until     DATETIME(6) NULL,                                     -- reserved for time-boxed holds
    PRIMARY KEY (show_id, seat_label),                                   -- lock order = this index order
    KEY idx_seats_reservation (reservation_id),
    CONSTRAINT fk_seats_show FOREIGN KEY (show_id) REFERENCES shows (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- A row exists only for a committed reservation: declined requests roll back their insert,
-- which also frees the idempotency key for a fresh attempt.
CREATE TABLE reservations (
    id              CHAR(36)     NOT NULL,
    show_id         CHAR(36)     NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,                               -- sha256(show_id:sorted seats)
    seats           JSON         NOT NULL,
    amount_paise    BIGINT       NOT NULL,
    status          ENUM ('confirmed', 'cancelled') NOT NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    cancelled_at    DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_reservations_user_key (user_id, idempotency_key),      -- BR-05, BR-06
    KEY idx_reservations_show (show_id, status),
    CONSTRAINT fk_reservations_show FOREIGN KEY (show_id) REFERENCES shows (id),
    CONSTRAINT chk_reservations_amount CHECK (amount_paise >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- Seats currently held by a user for a show; the guarded UPDATE on this row is the
-- per-user limit decision (BR-03).
CREATE TABLE user_show_counts (
    show_id CHAR(36)    NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    held    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (show_id, user_id),
    CONSTRAINT chk_counts_held CHECK (held >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
