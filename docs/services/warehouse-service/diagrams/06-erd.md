# Warehouse Service - Entity-Relationship Diagram (ERD)

```mermaid
erDiagram
    STORES ||--o{ STORE_ZONES : contains
    STORES ||--o{ STORE_HOURS : operates
    STORE_ZONES ||--o{ STORE_ZONE_INVENTORY : tracks

    STORES {
        uuid id PK
        string name
        text address
        string city
        string state
        string pincode
        decimal latitude "precision 10, scale 8"
        decimal longitude "precision 11, scale 8"
        string timezone
        string status "ACTIVE|INACTIVE|MAINTENANCE|CLOSED"
        int capacity_orders_per_hour
        timestamp created_at
        timestamp updated_at
        bigint version "optimistic lock"
        geometry location "PostGIS POINT"
    }

    STORE_ZONES {
        uuid id PK
        uuid store_id FK
        string zone_code UK
        string zone_name
        int aisle
        int shelf_level
        timestamp created_at
    }

    STORE_HOURS {
        uuid id PK
        uuid store_id FK
        int day_of_week "0-6, 0=Sunday"
        time open_time
        time close_time
        timestamp created_at
    }

    STORE_ZONE_INVENTORY {
        uuid id PK
        uuid zone_id FK
        uuid product_id FK
        int quantity_available
        int low_stock_threshold
        timestamp last_updated
    }
```

## PostGIS Indexes

```sql
-- Geospatial index for efficient ST_DWithin queries
CREATE INDEX idx_stores_location_gist ON stores USING GIST(location);

-- Regular indexes for common lookups
CREATE INDEX idx_stores_status ON stores(status);
CREATE INDEX idx_stores_city_status ON stores(city, status);
CREATE INDEX idx_store_zones_store_id ON store_zones(store_id);
CREATE INDEX idx_store_hours_store_id ON store_hours(store_id);
CREATE INDEX idx_store_hours_day ON store_hours(day_of_week);
```

## Data Model Details

```markdown
## Store Entity

- **id**: UUID primary key
- **location**: PostGIS POINT geometry (longitude, latitude, SRID=4326)
- **timezone**: UTC or regional (e.g., Asia/Kolkata)
- **status**: Controls availability for order assignment
- **capacity_orders_per_hour**: Rate limiting for store
- **version**: Optimistic lock for concurrent updates

## StoreZone Entity

- **zone_code**: Unique identifier (e.g., "ZONE_A_1")
- **aisle**: Physical aisle number
- **shelf_level**: Shelf height (1-5)
- Composite uniqueness: (store_id, zone_code)

## StoreHours Entity

- **day_of_week**: 0=Sunday through 6=Saturday
- **open_time, close_time**: TIME fields (HH:MM:SS)
- Multiple entries per store for different hours on different days

## Cache Keys

- `stores:{latitude}:{longitude}:{radius_km}` -> List<Store>
- `store_zones:{store_id}` -> List<Zone>
- `store_hours:{store_id}` -> List<Hours>
- TTL: 5-10 minutes based on data type
```

## Relationships & Constraints

```mermaid
graph TB
    A["Store.id"] -->|1:N| B["StoreZone.store_id"]
    A -->|1:N| C["StoreHours.store_id"]
    B -->|UNIQUE| D["zone_code"]
    C -->|Multiple per day| E["day_of_week"]

    F["Uniqueness Constraints"]
    F --> D

    G["Foreign Key Cascade"]
    G -->|ON DELETE CASCADE| B
    G -->|ON DELETE CASCADE| C
```
