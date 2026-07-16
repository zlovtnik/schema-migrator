-- object: wireless_frame_app_signals
-- folder: tables
-- depends_on: wireless_frames
create table if not exists wireless_frame_app_signals (
  dedupe_key text primary key references wireless_frames(dedupe_key) on delete cascade,
  ssdp_message_type text,
  ssdp_st text,
  ssdp_mx text,
  ssdp_usn text,
  dhcp_requested_ip text,
  dhcp_hostname text,
  dhcp_vendor_class text,
  dns_query_name text,
  mdns_name text
);

do $$
begin
  if exists (
    select 1 from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'wireless_frames'
      and column_name = 'ssdp_message_type'
  ) then
    execute $backfill$
      insert into wireless_frame_app_signals (
        dedupe_key, ssdp_message_type, ssdp_st, ssdp_mx, ssdp_usn,
        dhcp_requested_ip, dhcp_hostname, dhcp_vendor_class, dns_query_name, mdns_name
      )
      select
        dedupe_key, ssdp_message_type, ssdp_st, ssdp_mx, ssdp_usn,
        dhcp_requested_ip, dhcp_hostname, dhcp_vendor_class, dns_query_name, mdns_name
      from wireless_frames
      on conflict (dedupe_key) do update set
        ssdp_message_type = excluded.ssdp_message_type,
        ssdp_st = excluded.ssdp_st,
        ssdp_mx = excluded.ssdp_mx,
        ssdp_usn = excluded.ssdp_usn,
        dhcp_requested_ip = excluded.dhcp_requested_ip,
        dhcp_hostname = excluded.dhcp_hostname,
        dhcp_vendor_class = excluded.dhcp_vendor_class,
        dns_query_name = excluded.dns_query_name,
        mdns_name = excluded.mdns_name
    $backfill$;
  end if;
end;
$$;
