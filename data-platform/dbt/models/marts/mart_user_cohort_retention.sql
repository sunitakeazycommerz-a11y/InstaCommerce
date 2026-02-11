-- Weekly cohort retention analysis
WITH user_history AS (
    SELECT * FROM {{ ref('int_user_order_history') }}
),
orders AS (
    SELECT * FROM {{ ref('stg_orders') }}
),
cohort_orders AS (
    SELECT
        uh.cohort_week,
        DATE_TRUNC(o.order_date, WEEK) AS order_week,
        DATE_DIFF(DATE_TRUNC(o.order_date, WEEK), uh.cohort_week, WEEK) AS weeks_since_signup,
        o.user_id,
        o.order_id
    FROM orders o
    INNER JOIN user_history uh ON o.user_id = uh.user_id
    WHERE o.status = 'DELIVERED'
)
SELECT
    cohort_week,
    weeks_since_signup,
    COUNT(DISTINCT user_id) AS active_users,
    COUNT(DISTINCT order_id) AS total_orders,
    FIRST_VALUE(COUNT(DISTINCT user_id)) OVER (
        PARTITION BY cohort_week ORDER BY weeks_since_signup
        ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
    ) AS cohort_size,
    SAFE_DIVIDE(
        COUNT(DISTINCT user_id),
        FIRST_VALUE(COUNT(DISTINCT user_id)) OVER (
            PARTITION BY cohort_week ORDER BY weeks_since_signup
            ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
        )
    ) * 100 AS retention_pct
FROM cohort_orders
GROUP BY cohort_week, weeks_since_signup
ORDER BY cohort_week, weeks_since_signup
