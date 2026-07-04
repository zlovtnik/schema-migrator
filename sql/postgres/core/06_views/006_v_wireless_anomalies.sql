-- object: v_wireless_anomalies
-- folder: views
-- depends_on: sync_events_expanded
create or replace view v_wireless_anomalies as
select
  timeline.dedupe_key,
  timeline.observed_at,
  timeline.session_key,
  timeline.source_mac,
  timeline.destination_bssid,
  timeline.ssid,
  timeline.tsft_delta_us,
  timeline.wall_clock_delta_ms,
  timeline.mixed_encryption,
  timeline.large_frame,
  timeline.dedupe_or_replay_suspect,
  array_remove(array[
    case when timeline.large_frame then 'large_frame' end,
    case when timeline.mixed_encryption then 'mixed_encryption' end,
    case when timeline.dedupe_or_replay_suspect then 'dedupe_or_replay_suspect' end
  ], null) as reasons
from v_wireless_session_timeline timeline
where timeline.large_frame
   or timeline.mixed_encryption
   or timeline.dedupe_or_replay_suspect;
