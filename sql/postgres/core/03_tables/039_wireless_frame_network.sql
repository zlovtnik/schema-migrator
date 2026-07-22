-- object: wireless_frame_network
-- folder: tables
-- depends_on: wireless_frames
create table if not exists wireless_frame_network (
  dedupe_key text primary key references wireless_frames(dedupe_key) on delete cascade,
  llc_oui text,
  ethertype integer,
  ethertype_name text,
  src_ip text,
  dst_ip text,
  ip_ttl integer,
  ip_protocol integer,
  ip_protocol_name text,
  src_port integer,
  dst_port integer,
  transport_protocol text,
  transport_length integer,
  transport_checksum integer,
  app_protocol text
);

do $$
begin
  if exists (
    select 1 from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'wireless_frames'
      and column_name = 'llc_oui'
  ) then
    execute $backfill$
      insert into wireless_frame_network (
        dedupe_key, llc_oui, ethertype, ethertype_name, src_ip, dst_ip,
        ip_ttl, ip_protocol, ip_protocol_name, src_port, dst_port,
        transport_protocol, transport_length, transport_checksum, app_protocol
      )
      select
        dedupe_key, llc_oui, ethertype, ethertype_name, src_ip, dst_ip,
        ip_ttl, ip_protocol, ip_protocol_name, src_port, dst_port,
        transport_protocol, transport_length, transport_checksum, app_protocol
      from wireless_frames
      on conflict (dedupe_key) do update set
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
        app_protocol = excluded.app_protocol
    $backfill$;
  end if;
end;
$$;
