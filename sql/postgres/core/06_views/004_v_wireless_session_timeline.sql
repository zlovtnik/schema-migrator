-- object: v_wireless_session_timeline
-- folder: views
-- depends_on: sync_events_expanded
create or replace view v_wireless_session_timeline as
with base as (
  select
    ssi.dedupe_key,
    ssi.observed_at,
    coalesce(ssi.session_key, ssi.payload->>'session_key') as session_key,
    coalesce(ssi.retransmit_key, ssi.payload->>'retransmit_key') as retransmit_key,
    coalesce(ssi.frame_fingerprint, ssi.payload->>'frame_fingerprint') as frame_fingerprint,
    coalesce(ssi.source_mac, ssi.payload->>'source_mac') as source_mac,
    coalesce(ssi.destination_bssid, ssi.bssid, ssi.payload->>'destination_bssid', ssi.payload->>'bssid') as destination_bssid,
    coalesce(ssi.ssid, ssi.payload->>'ssid') as ssid,
    coalesce(ssi.protected, false) as protected,
    coalesce(ssi.large_frame, false) as large_frame,
    coalesce(ssi.dedupe_or_replay_suspect, false) as dedupe_or_replay_suspect,
    coalesce(ssi.tsft, coordinator.safe_bigint(ssi.payload->>'tsft')) as tsft
  from sync_events_expanded ssi
  where ssi.stream_name = 'wireless.audit'
)
select
  dedupe_key,
  observed_at,
  session_key,
  retransmit_key,
  frame_fingerprint,
  source_mac,
  destination_bssid,
  ssid,
  protected,
  large_frame,
  dedupe_or_replay_suspect,
  tsft,
  case
    when lag(tsft) over session_window is not null and tsft is not null
      then tsft - lag(tsft) over session_window
  end as tsft_delta_us,
  case
    when lag(observed_at) over session_window is not null
      then round(extract(epoch from (observed_at - lag(observed_at) over session_window)) * 1000)
  end as wall_clock_delta_ms,
  (
    bool_or(protected) over session_partition
    and bool_or(not protected) over session_partition
  ) as mixed_encryption
from base
window
  session_partition as (partition by session_key),
  session_window as (partition by session_key order by observed_at);
