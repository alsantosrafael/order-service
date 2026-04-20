CREATE TABLE processed_messages (
                                    id           BIGSERIAL PRIMARY KEY,
                                    message_id   VARCHAR(255) NOT NULL UNIQUE,
                                    processed_at TIMESTAMP NOT NULL DEFAULT NOW()
);