# Rider Fleet Service - Low-Level Design (LLD)

```mermaid
graph TB
    subgraph "HTTP Layer"
        RiderCtrl["RiderController<br/>POST /riders/assign<br/>POST /riders/{id}/location<br/>POST /riders/{id}/status"]
        ListCtrl["ListController<br/>GET /riders/available"]
    end

    subgraph "Service Layer"
        RiderSvc["RiderService<br/>- assignOrder()<br/>- updateLocation()<br/>- updateStatus()"]
        AvailabilitySvc["AvailabilityService<br/>- listAvailable()"]
        RecoverySvc["RecoverySvc<br/>- detectStuck()"]
    end

    subgraph "Repository Layer"
        RiderRepo["RiderRepository<br/>findById()<br/>@Version locking"]
        LocationRepo["LocationRepository"]
        AssignmentRepo["AssignmentRepository"]
    end

    subgraph "Infrastructure"
        Scheduler["StuckRider Scheduler<br/>Every 10 min"]
        KafkaProducer["Kafka Producer<br/>rider.events"]
    end

    subgraph "Data Layer"
        PostgreSQL["PostgreSQL<br/>- riders (@Version)<br/>- locations<br/>- assignments"]
    end

    RiderCtrl --> RiderSvc
    ListCtrl --> AvailabilitySvc
    RiderSvc --> RiderRepo
    RiderSvc --> LocationRepo
    RiderSvc --> KafkaProducer
    AvailabilitySvc --> RiderRepo
    Scheduler --> RecoverySvc
    RecoverySvc --> PostgreSQL
    RiderRepo -->|SELECT...with locking| PostgreSQL
    LocationRepo --> PostgreSQL
```

## Rider Status Transitions

```sql
create table riders (
    id uuid primary key,
    phone varchar unique,
    status varchar(20) not null,  -- AVAILABLE, ASSIGNED, ON_DELIVERY, OFF_DUTY
    current_order_id uuid,
    current_lat decimal,
    current_long decimal,
    created_at timestamp,
    updated_at timestamp,
    version bigint  -- Optimistic locking
);

create table rider_locations (
    id uuid primary key,
    rider_id uuid references riders(id),
    latitude decimal,
    longitude decimal,
    updated_at timestamp
);

create table assignment_history (
    id uuid primary key,
    rider_id uuid references riders(id),
    order_id uuid,
    assigned_at timestamp,
    completed_at timestamp,
    status varchar(20)
);

create index idx_riders_status on riders(status);
create index idx_riders_phone on riders(phone);
create index idx_locations_rider_id on rider_locations(rider_id);
```
