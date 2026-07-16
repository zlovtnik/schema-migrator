-- object: wireless_frame_identity
-- folder: tables
-- depends_on: wireless_frames, wireless_frame_network
create table if not exists wireless_frame_identity (
  dedupe_key text primary key references wireless_frames(dedupe_key) on delete cascade,
  username text,
  event_type text,
  session_key text,
  retransmit_key text,
  frame_fingerprint text,
  payload_visibility text,
  identity_source text,
  device_fingerprint text,
  wps_device_name text,
  wps_manufacturer text,
  wps_model_name text,
  handshake_captured boolean not null default false,
  search_tsv tsvector
);

do $$
begin
  if exists (
    select 1 from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'wireless_frames'
      and column_name = 'username'
  ) then
    execute $backfill$
      insert into wireless_frame_identity (
        dedupe_key, username, event_type, session_key, retransmit_key,
        frame_fingerprint, payload_visibility, identity_source, device_fingerprint,
        wps_device_name, wps_manufacturer, wps_model_name, handshake_captured
      )
      select
        dedupe_key, username, event_type, session_key, retransmit_key,
        frame_fingerprint, payload_visibility, identity_source, device_fingerprint,
        wps_device_name, wps_manufacturer, wps_model_name, handshake_captured
      from wireless_frames
      on conflict (dedupe_key) do update set
        username = excluded.username,
        event_type = excluded.event_type,
        session_key = excluded.session_key,
        retransmit_key = excluded.retransmit_key,
        frame_fingerprint = excluded.frame_fingerprint,
        payload_visibility = excluded.payload_visibility,
        identity_source = excluded.identity_source,
        device_fingerprint = excluded.device_fingerprint,
        wps_device_name = excluded.wps_device_name,
        wps_manufacturer = excluded.wps_manufacturer,
        wps_model_name = excluded.wps_model_name,
        handshake_captured = excluded.handshake_captured
    $backfill$;
  end if;
end;
$$;

create or replace function wireless_frame_identity_search_tsv()
returns trigger
language plpgsql
as $$
declare
  core wireless_frames%rowtype;
  network wireless_frame_network%rowtype;
begin
  select * into core from wireless_frames where dedupe_key = new.dedupe_key;
  select * into network from wireless_frame_network where dedupe_key = new.dedupe_key;

  new.search_tsv := to_tsvector(
    'simple'::regconfig,
    lower(concat_ws(
      ' ', core.sensor_id, core.source_mac, core.bssid, core.destination_bssid, core.ssid,
      new.wps_device_name, new.wps_manufacturer, new.wps_model_name, new.device_fingerprint,
      network.app_protocol, network.src_ip, network.dst_ip, new.username
    ))
  );
  return new;
end;
$$;

drop trigger if exists wireless_frame_identity_search_tsv on wireless_frame_identity;
create trigger wireless_frame_identity_search_tsv
before insert or update on wireless_frame_identity
for each row execute function wireless_frame_identity_search_tsv();
