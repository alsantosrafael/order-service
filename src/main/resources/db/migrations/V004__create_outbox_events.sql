CREATE TABLE outbox_events (
                               id            BIGSERIAL PRIMARY KEY,
                               aggregate_id  VARCHAR(255) NOT NULL,
                               event_type    VARCHAR(100) NOT NULL,
                               payload       TEXT NOT NULL,
                               created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
                               published     BOOLEAN NOT NULL DEFAULT FALSE,
                               published_at  TIMESTAMP
);
CREATE INDEX idx_outbox_pending ON outbox_events(published) WHERE published = FALSE;