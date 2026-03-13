ALTER TABLE deliveries ADD COLUMN eta_low_minutes INTEGER;
ALTER TABLE deliveries ADD COLUMN eta_high_minutes INTEGER;
ALTER TABLE deliveries ADD COLUMN last_eta_updated_at TIMESTAMP;
