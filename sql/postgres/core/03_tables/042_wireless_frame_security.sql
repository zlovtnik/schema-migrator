-- object: wireless_frame_security
-- folder: tables
-- depends_on: wireless_frames
create table if not exists wireless_frame_security (
  dedupe_key text primary key references wireless_frames(dedupe_key) on delete cascade,
  large_frame boolean not null default false,
  mixed_encryption boolean,
  dedupe_or_replay_suspect boolean not null default false,
  raw_len integer not null default 0,
  security_flags integer not null default 0,
  risk_score double precision,
  tags jsonb not null default '[]'::jsonb,
  signal_status text,
  adjacent_mac_hint text
);

do $$
begin
  if exists (
    select 1 from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'wireless_frames'
      and column_name = 'large_frame'
  ) then
    execute $backfill$
      insert into wireless_frame_security (
        dedupe_key, large_frame, mixed_encryption, dedupe_or_replay_suspect,
        raw_len, security_flags, risk_score, tags, signal_status, adjacent_mac_hint
      )
      select
        dedupe_key, large_frame, mixed_encryption, dedupe_or_replay_suspect,
        raw_len, security_flags, risk_score, tags, signal_status, adjacent_mac_hint
      from wireless_frames
      on conflict (dedupe_key) do update set
        large_frame = excluded.large_frame,
        mixed_encryption = excluded.mixed_encryption,
        dedupe_or_replay_suspect = excluded.dedupe_or_replay_suspect,
        raw_len = excluded.raw_len,
        security_flags = excluded.security_flags,
        risk_score = excluded.risk_score,
        tags = excluded.tags,
        signal_status = excluded.signal_status,
        adjacent_mac_hint = excluded.adjacent_mac_hint
    $backfill$;
  end if;
end;
$$;

create or replace function wireless_frames_legacy_to_split()
returns trigger
language plpgsql
as $$
declare
  legacy jsonb := to_jsonb(new);
  old_legacy jsonb := to_jsonb(old);
begin
  if pg_trigger_depth() > 1 or not (legacy ? 'signal_dbm') then
    return new;
  end if;

  if tg_op = 'INSERT'
     or legacy->'signal_dbm' is distinct from old_legacy->'signal_dbm'
     or legacy->'noise_dbm' is distinct from old_legacy->'noise_dbm'
     or legacy->'frequency_mhz' is distinct from old_legacy->'frequency_mhz'
     or legacy->'channel_flags' is distinct from old_legacy->'channel_flags'
     or legacy->'data_rate_kbps' is distinct from old_legacy->'data_rate_kbps'
     or legacy->'antenna_id' is distinct from old_legacy->'antenna_id'
     or legacy->'tsft' is distinct from old_legacy->'tsft'
     or legacy->'fragment_number' is distinct from old_legacy->'fragment_number'
     or legacy->'channel_number' is distinct from old_legacy->'channel_number'
     or legacy->'tsft_delta_us' is distinct from old_legacy->'tsft_delta_us'
     or legacy->'wall_clock_delta_ms' is distinct from old_legacy->'wall_clock_delta_ms' then
    insert into wireless_frame_radio (
    dedupe_key, signal_dbm, noise_dbm, frequency_mhz, channel_flags,
    data_rate_kbps, antenna_id, tsft, fragment_number, channel_number,
    tsft_delta_us, wall_clock_delta_ms
  ) values (
    new.dedupe_key,
    (legacy->>'signal_dbm')::integer,
    (legacy->>'noise_dbm')::integer,
    (legacy->>'frequency_mhz')::integer,
    (legacy->>'channel_flags')::integer,
    (legacy->>'data_rate_kbps')::integer,
    (legacy->>'antenna_id')::integer,
    (legacy->>'tsft')::bigint,
    (legacy->>'fragment_number')::integer,
    (legacy->>'channel_number')::integer,
    (legacy->>'tsft_delta_us')::bigint,
    (legacy->>'wall_clock_delta_ms')::bigint
    ) on conflict (dedupe_key) do update set
      signal_dbm = excluded.signal_dbm,
      noise_dbm = excluded.noise_dbm,
      frequency_mhz = excluded.frequency_mhz,
      channel_flags = excluded.channel_flags,
      data_rate_kbps = excluded.data_rate_kbps,
      antenna_id = excluded.antenna_id,
      tsft = excluded.tsft,
      fragment_number = excluded.fragment_number,
      channel_number = excluded.channel_number,
      tsft_delta_us = excluded.tsft_delta_us,
      wall_clock_delta_ms = excluded.wall_clock_delta_ms;
  end if;

  if tg_op = 'INSERT'
     or legacy->'qos_tid' is distinct from old_legacy->'qos_tid'
     or legacy->'qos_eosp' is distinct from old_legacy->'qos_eosp'
     or legacy->'qos_ack_policy' is distinct from old_legacy->'qos_ack_policy'
     or legacy->'qos_ack_policy_label' is distinct from old_legacy->'qos_ack_policy_label'
     or legacy->'qos_amsdu' is distinct from old_legacy->'qos_amsdu'
     or legacy->'more_data' is distinct from old_legacy->'more_data'
     or legacy->'retry' is distinct from old_legacy->'retry'
     or legacy->'power_save' is distinct from old_legacy->'power_save'
     or legacy->'protected' is distinct from old_legacy->'protected'
     or legacy->'frame_control_flags' is distinct from old_legacy->'frame_control_flags' then
    insert into wireless_frame_qos (
    dedupe_key, qos_tid, qos_eosp, qos_ack_policy, qos_ack_policy_label,
    qos_amsdu, more_data, retry, power_save, protected, frame_control_flags
  ) values (
    new.dedupe_key,
    (legacy->>'qos_tid')::integer,
    (legacy->>'qos_eosp')::boolean,
    (legacy->>'qos_ack_policy')::integer,
    legacy->>'qos_ack_policy_label',
    (legacy->>'qos_amsdu')::boolean,
    coalesce((legacy->>'more_data')::boolean, false),
    coalesce((legacy->>'retry')::boolean, false),
    coalesce((legacy->>'power_save')::boolean, false),
    coalesce((legacy->>'protected')::boolean, false),
    coalesce((legacy->>'frame_control_flags')::integer, 0)
    ) on conflict (dedupe_key) do update set
      qos_tid = excluded.qos_tid,
      qos_eosp = excluded.qos_eosp,
      qos_ack_policy = excluded.qos_ack_policy,
      qos_ack_policy_label = excluded.qos_ack_policy_label,
      qos_amsdu = excluded.qos_amsdu,
      more_data = excluded.more_data,
      retry = excluded.retry,
      power_save = excluded.power_save,
      protected = excluded.protected,
      frame_control_flags = excluded.frame_control_flags;
  end if;

  if tg_op = 'INSERT'
     or legacy->'llc_oui' is distinct from old_legacy->'llc_oui'
     or legacy->'ethertype' is distinct from old_legacy->'ethertype'
     or legacy->'ethertype_name' is distinct from old_legacy->'ethertype_name'
     or legacy->'src_ip' is distinct from old_legacy->'src_ip'
     or legacy->'dst_ip' is distinct from old_legacy->'dst_ip'
     or legacy->'ip_ttl' is distinct from old_legacy->'ip_ttl'
     or legacy->'ip_protocol' is distinct from old_legacy->'ip_protocol'
     or legacy->'ip_protocol_name' is distinct from old_legacy->'ip_protocol_name'
     or legacy->'src_port' is distinct from old_legacy->'src_port'
     or legacy->'dst_port' is distinct from old_legacy->'dst_port'
     or legacy->'transport_protocol' is distinct from old_legacy->'transport_protocol'
     or legacy->'transport_length' is distinct from old_legacy->'transport_length'
     or legacy->'transport_checksum' is distinct from old_legacy->'transport_checksum'
     or legacy->'app_protocol' is distinct from old_legacy->'app_protocol' then
    insert into wireless_frame_network (
    dedupe_key, llc_oui, ethertype, ethertype_name, src_ip, dst_ip,
    ip_ttl, ip_protocol, ip_protocol_name, src_port, dst_port,
    transport_protocol, transport_length, transport_checksum, app_protocol
  ) values (
    new.dedupe_key,
    legacy->>'llc_oui',
    (legacy->>'ethertype')::integer,
    legacy->>'ethertype_name',
    legacy->>'src_ip',
    legacy->>'dst_ip',
    (legacy->>'ip_ttl')::integer,
    (legacy->>'ip_protocol')::integer,
    legacy->>'ip_protocol_name',
    (legacy->>'src_port')::integer,
    (legacy->>'dst_port')::integer,
    legacy->>'transport_protocol',
    (legacy->>'transport_length')::integer,
    (legacy->>'transport_checksum')::integer,
    legacy->>'app_protocol'
    ) on conflict (dedupe_key) do update set
      llc_oui = excluded.llc_oui,
      ethertype = excluded.ethertype,
      ethertype_name = excluded.ethertype_name,
      src_ip = excluded.src_ip,
      dst_ip = excluded.dst_ip,
      ip_ttl = excluded.ip_ttl,
      ip_protocol = excluded.ip_protocol,
      ip_protocol_name = excluded.ip_protocol_name,
      src_port = excluded.src_port,
      dst_port = excluded.dst_port,
      transport_protocol = excluded.transport_protocol,
      transport_length = excluded.transport_length,
      transport_checksum = excluded.transport_checksum,
      app_protocol = excluded.app_protocol;
  end if;

  if tg_op = 'INSERT'
     or legacy->'ssdp_message_type' is distinct from old_legacy->'ssdp_message_type'
     or legacy->'ssdp_st' is distinct from old_legacy->'ssdp_st'
     or legacy->'ssdp_mx' is distinct from old_legacy->'ssdp_mx'
     or legacy->'ssdp_usn' is distinct from old_legacy->'ssdp_usn'
     or legacy->'dhcp_requested_ip' is distinct from old_legacy->'dhcp_requested_ip'
     or legacy->'dhcp_hostname' is distinct from old_legacy->'dhcp_hostname'
     or legacy->'dhcp_vendor_class' is distinct from old_legacy->'dhcp_vendor_class'
     or legacy->'dns_query_name' is distinct from old_legacy->'dns_query_name'
     or legacy->'mdns_name' is distinct from old_legacy->'mdns_name' then
    insert into wireless_frame_app_signals (
    dedupe_key, ssdp_message_type, ssdp_st, ssdp_mx, ssdp_usn,
    dhcp_requested_ip, dhcp_hostname, dhcp_vendor_class, dns_query_name, mdns_name
  ) values (
    new.dedupe_key,
    legacy->>'ssdp_message_type',
    legacy->>'ssdp_st',
    legacy->>'ssdp_mx',
    legacy->>'ssdp_usn',
    legacy->>'dhcp_requested_ip',
    legacy->>'dhcp_hostname',
    legacy->>'dhcp_vendor_class',
    legacy->>'dns_query_name',
    legacy->>'mdns_name'
    ) on conflict (dedupe_key) do update set
      ssdp_message_type = excluded.ssdp_message_type,
      ssdp_st = excluded.ssdp_st,
      ssdp_mx = excluded.ssdp_mx,
      ssdp_usn = excluded.ssdp_usn,
      dhcp_requested_ip = excluded.dhcp_requested_ip,
      dhcp_hostname = excluded.dhcp_hostname,
      dhcp_vendor_class = excluded.dhcp_vendor_class,
      dns_query_name = excluded.dns_query_name,
      mdns_name = excluded.mdns_name;
  end if;

  if tg_op = 'INSERT'
     or legacy->'username' is distinct from old_legacy->'username'
     or legacy->'event_type' is distinct from old_legacy->'event_type'
     or legacy->'session_key' is distinct from old_legacy->'session_key'
     or legacy->'retransmit_key' is distinct from old_legacy->'retransmit_key'
     or legacy->'frame_fingerprint' is distinct from old_legacy->'frame_fingerprint'
     or legacy->'payload_visibility' is distinct from old_legacy->'payload_visibility'
     or legacy->'identity_source' is distinct from old_legacy->'identity_source'
     or legacy->'device_fingerprint' is distinct from old_legacy->'device_fingerprint'
     or legacy->'wps_device_name' is distinct from old_legacy->'wps_device_name'
     or legacy->'wps_manufacturer' is distinct from old_legacy->'wps_manufacturer'
     or legacy->'wps_model_name' is distinct from old_legacy->'wps_model_name'
     or legacy->'handshake_captured' is distinct from old_legacy->'handshake_captured' then
    insert into wireless_frame_identity (
    dedupe_key, username, event_type, session_key, retransmit_key,
    frame_fingerprint, payload_visibility, identity_source, device_fingerprint,
    wps_device_name, wps_manufacturer, wps_model_name, handshake_captured
  ) values (
    new.dedupe_key,
    legacy->>'username',
    legacy->>'event_type',
    legacy->>'session_key',
    legacy->>'retransmit_key',
    legacy->>'frame_fingerprint',
    legacy->>'payload_visibility',
    legacy->>'identity_source',
    legacy->>'device_fingerprint',
    legacy->>'wps_device_name',
    legacy->>'wps_manufacturer',
    legacy->>'wps_model_name',
    coalesce((legacy->>'handshake_captured')::boolean, false)
    ) on conflict (dedupe_key) do update set
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
      handshake_captured = excluded.handshake_captured;
  end if;

  if tg_op = 'INSERT'
     or legacy->'large_frame' is distinct from old_legacy->'large_frame'
     or legacy->'mixed_encryption' is distinct from old_legacy->'mixed_encryption'
     or legacy->'dedupe_or_replay_suspect' is distinct from old_legacy->'dedupe_or_replay_suspect'
     or legacy->'raw_len' is distinct from old_legacy->'raw_len'
     or legacy->'security_flags' is distinct from old_legacy->'security_flags'
     or legacy->'risk_score' is distinct from old_legacy->'risk_score'
     or legacy->'tags' is distinct from old_legacy->'tags'
     or legacy->'signal_status' is distinct from old_legacy->'signal_status'
     or legacy->'adjacent_mac_hint' is distinct from old_legacy->'adjacent_mac_hint' then
    insert into wireless_frame_security (
    dedupe_key, large_frame, mixed_encryption, dedupe_or_replay_suspect,
    raw_len, security_flags, risk_score, tags, signal_status, adjacent_mac_hint
  ) values (
    new.dedupe_key,
    coalesce((legacy->>'large_frame')::boolean, false),
    (legacy->>'mixed_encryption')::boolean,
    coalesce((legacy->>'dedupe_or_replay_suspect')::boolean, false),
    coalesce((legacy->>'raw_len')::integer, 0),
    coalesce((legacy->>'security_flags')::integer, 0),
    (legacy->>'risk_score')::double precision,
    coalesce(legacy->'tags', '[]'::jsonb),
    legacy->>'signal_status',
    legacy->>'adjacent_mac_hint'
    ) on conflict (dedupe_key) do update set
      large_frame = excluded.large_frame,
      mixed_encryption = excluded.mixed_encryption,
      dedupe_or_replay_suspect = excluded.dedupe_or_replay_suspect,
      raw_len = excluded.raw_len,
      security_flags = excluded.security_flags,
      risk_score = excluded.risk_score,
      tags = excluded.tags,
      signal_status = excluded.signal_status,
      adjacent_mac_hint = excluded.adjacent_mac_hint;
  end if;

  return new;
end;
$$;

create or replace function wireless_frame_split_to_legacy()
returns trigger
language plpgsql
as $$
begin
  if pg_trigger_depth() > 1 or not exists (
    select 1 from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'wireless_frames'
      and column_name = 'signal_dbm'
  ) then
    return new;
  end if;

  if tg_table_name = 'wireless_frame_radio' then
    execute $sync$
      update wireless_frames set
        signal_dbm = $1, noise_dbm = $2, frequency_mhz = $3,
        channel_flags = $4, data_rate_kbps = $5, antenna_id = $6,
        tsft = $7, fragment_number = $8, channel_number = $9,
        tsft_delta_us = $10, wall_clock_delta_ms = $11
      where dedupe_key = $12
    $sync$ using
      new.signal_dbm, new.noise_dbm, new.frequency_mhz,
      new.channel_flags, new.data_rate_kbps, new.antenna_id,
      new.tsft, new.fragment_number, new.channel_number,
      new.tsft_delta_us, new.wall_clock_delta_ms, new.dedupe_key;
  elsif tg_table_name = 'wireless_frame_qos' then
    execute $sync$
      update wireless_frames set
        qos_tid = $1, qos_eosp = $2, qos_ack_policy = $3,
        qos_ack_policy_label = $4, qos_amsdu = $5,
        more_data = $6, retry = $7, power_save = $8,
        protected = $9, frame_control_flags = $10
      where dedupe_key = $11
    $sync$ using
      new.qos_tid, new.qos_eosp, new.qos_ack_policy,
      new.qos_ack_policy_label, new.qos_amsdu,
      new.more_data, new.retry, new.power_save,
      new.protected, new.frame_control_flags, new.dedupe_key;
  elsif tg_table_name = 'wireless_frame_network' then
    execute $sync$
      update wireless_frames set
        llc_oui = $1, ethertype = $2, ethertype_name = $3,
        src_ip = $4, dst_ip = $5, ip_ttl = $6, ip_protocol = $7,
        ip_protocol_name = $8, src_port = $9, dst_port = $10,
        transport_protocol = $11, transport_length = $12,
        transport_checksum = $13, app_protocol = $14
      where dedupe_key = $15
    $sync$ using
      new.llc_oui, new.ethertype, new.ethertype_name,
      new.src_ip, new.dst_ip, new.ip_ttl, new.ip_protocol,
      new.ip_protocol_name, new.src_port, new.dst_port,
      new.transport_protocol, new.transport_length,
      new.transport_checksum, new.app_protocol, new.dedupe_key;
  elsif tg_table_name = 'wireless_frame_app_signals' then
    execute $sync$
      update wireless_frames set
        ssdp_message_type = $1, ssdp_st = $2, ssdp_mx = $3, ssdp_usn = $4,
        dhcp_requested_ip = $5, dhcp_hostname = $6, dhcp_vendor_class = $7,
        dns_query_name = $8, mdns_name = $9
      where dedupe_key = $10
    $sync$ using
      new.ssdp_message_type, new.ssdp_st, new.ssdp_mx, new.ssdp_usn,
      new.dhcp_requested_ip, new.dhcp_hostname, new.dhcp_vendor_class,
      new.dns_query_name, new.mdns_name, new.dedupe_key;
  elsif tg_table_name = 'wireless_frame_identity' then
    execute $sync$
      update wireless_frames set
        username = $1, event_type = $2, session_key = $3, retransmit_key = $4,
        frame_fingerprint = $5, payload_visibility = $6, identity_source = $7,
        device_fingerprint = $8, wps_device_name = $9,
        wps_manufacturer = $10, wps_model_name = $11, handshake_captured = $12
      where dedupe_key = $13
    $sync$ using
      new.username, new.event_type, new.session_key, new.retransmit_key,
      new.frame_fingerprint, new.payload_visibility, new.identity_source,
      new.device_fingerprint, new.wps_device_name,
      new.wps_manufacturer, new.wps_model_name, new.handshake_captured, new.dedupe_key;
  elsif tg_table_name = 'wireless_frame_security' then
    execute $sync$
      update wireless_frames set
        large_frame = $1, mixed_encryption = $2, dedupe_or_replay_suspect = $3,
        raw_len = $4, security_flags = $5, risk_score = $6, tags = $7,
        signal_status = $8, adjacent_mac_hint = $9
      where dedupe_key = $10
    $sync$ using
      new.large_frame, new.mixed_encryption, new.dedupe_or_replay_suspect,
      new.raw_len, new.security_flags, new.risk_score, new.tags,
      new.signal_status, new.adjacent_mac_hint, new.dedupe_key;
  end if;

  return new;
end;
$$;

drop trigger if exists wireless_frames_legacy_to_split on wireless_frames;
create trigger wireless_frames_legacy_to_split
after insert or update on wireless_frames
for each row execute function wireless_frames_legacy_to_split();

drop trigger if exists wireless_frame_radio_to_legacy on wireless_frame_radio;
create trigger wireless_frame_radio_to_legacy after insert or update on wireless_frame_radio
for each row execute function wireless_frame_split_to_legacy();

drop trigger if exists wireless_frame_qos_to_legacy on wireless_frame_qos;
create trigger wireless_frame_qos_to_legacy after insert or update on wireless_frame_qos
for each row execute function wireless_frame_split_to_legacy();

drop trigger if exists wireless_frame_network_to_legacy on wireless_frame_network;
create trigger wireless_frame_network_to_legacy after insert or update on wireless_frame_network
for each row execute function wireless_frame_split_to_legacy();

drop trigger if exists wireless_frame_app_signals_to_legacy on wireless_frame_app_signals;
create trigger wireless_frame_app_signals_to_legacy after insert or update on wireless_frame_app_signals
for each row execute function wireless_frame_split_to_legacy();

drop trigger if exists wireless_frame_identity_to_legacy on wireless_frame_identity;
create trigger wireless_frame_identity_to_legacy after insert or update on wireless_frame_identity
for each row execute function wireless_frame_split_to_legacy();

drop trigger if exists wireless_frame_security_to_legacy on wireless_frame_security;
create trigger wireless_frame_security_to_legacy after insert or update on wireless_frame_security
for each row execute function wireless_frame_split_to_legacy();
