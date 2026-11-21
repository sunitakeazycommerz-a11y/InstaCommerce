ALTER TABLE notification_log
    ADD COLUMN IF NOT EXISTS template_context TEXT;
