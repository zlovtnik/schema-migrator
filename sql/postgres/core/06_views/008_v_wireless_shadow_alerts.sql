-- object: v_wireless_shadow_alerts
-- folder: views
-- depends_on: wireless_shadow_alerts
create or replace view v_wireless_shadow_alerts as
select
  source_mac as alert_id,
  source_mac as dedupe_key,
  source_mac,
  first_occurred_at,
  last_occurred_at,
  last_occurred_at as observed_at,
  occurrence_count,
  destination_bssid,
  ssid,
  sensor_id,
  location_id,
  signal_dbm,
  reason,
  evidence,
  resolved_at,
  created_at,
  updated_at
from wireless_shadow_alerts
order by last_occurred_at desc;
