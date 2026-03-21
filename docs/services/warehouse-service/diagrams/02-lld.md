# Warehouse Service - Low-Level Design (LLD)

```mermaid
graph TB
    subgraph "HTTP Layer"
        StoreCtrl["StoreController<br/>GET /stores/nearest<br/>POST /stores/{id}/zones"]
        HoursCtrl["HoursController<br/>POST /stores/{id}/hours<br/>GET /stores/{id}/hours"]
    end

    subgraph "Service Layer"
        StoreSvc["StoreService<br/>- findNearest()<br/>- createStore()<br/>- updateStore()"]
        ZoneSvc["ZoneService<br/>- createZone()<br/>- listZones()"]
        HoursSvc["HoursService<br/>- setHours()<br/>- getHours()"]
    end

    subgraph "Repository Layer"
        StoreRepo["StoreRepository<br/>findByLocation(geo)<br/>findById()"]
        ZoneRepo["ZoneRepository"]
        HoursRepo["HoursRepository"]
    end

    subgraph "Cache Layer"
        CaffeineMgr["CaffeineCacheManager<br/>@Cacheable, @CacheEvict"]
        RedisOpt["RedisCache<br/>(optional for distributed)"]
    end

    subgraph "Data Layer"
        PostgreSQL["PostgreSQL + PostGIS<br/>- stores (geometry)<br/>- zones<br/>- hours"]
    end

    StoreCtrl --> StoreSvc
    HoursCtrl --> HoursSvc
    StoreSvc --> CaffeineMgr
    CaffeineMgr -->|Cache miss| StoreRepo
    StoreRepo --> PostgreSQL
    ZoneSvc --> ZoneRepo
    HoursSvc --> HoursRepo
    ZoneRepo --> PostgreSQL
    HoursRepo --> PostgreSQL

    style CaffeineMgr fill:#fff59d
    style RedisOpt fill:#fff59d
```

## Cache Strategy

```mermaid
graph TD
    A["GET /stores/nearest"] -->|Check L1| B["Caffeine<br/>local cache"]
    B -->|Hit<br/>TTL=5min| C["Return cached<br/><50ms"]
    B -->|Miss| D["Check L2<br/>Redis"]
    D -->|Hit| E["Load to L1<br/>TTL=5min"]
    E -->|Return<br/>~100ms| C
    D -->|Miss| F["Query DB<br/>PostGIS"]
    F -->|Results| G["Store in L2<br/>TTL=10min"]
    G -->|Store in L1| E
```

## Database Schema

```sql
create table stores (
    id uuid primary key,
    name varchar not null,
    address text not null,
    city varchar(100),
    state varchar(100),
    pincode varchar(10),
    latitude numeric(10, 8),
    longitude numeric(11, 8),
    timezone varchar(50) default 'UTC',
    status varchar(30) default 'ACTIVE',
    capacity_orders_per_hour int default 100,
    created_at timestamp,
    updated_at timestamp,
    version bigint,
    -- PostGIS geometry type
    location geometry(POINT, 4326)
);

create index idx_stores_location_gist on stores using gist(location);
create index idx_stores_status on stores(status);

create table store_zones (
    id uuid primary key,
    store_id uuid references stores(id),
    zone_code varchar unique,
    zone_name varchar,
    aisle int,
    shelf_level int,
    created_at timestamp
);

create table store_hours (
    id uuid primary key,
    store_id uuid references stores(id),
    day_of_week int,  -- 0-6 (Sun-Sat)
    open_time time,
    close_time time,
    created_at timestamp
);
```
