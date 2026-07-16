-- object: wireless_frame_radio
-- folder: tables
-- depends_on: wireless_frames
create table if not exists wireless_frame_radio (
  dedupe_key text primary key references wireless_frames(dedupe_key) on delete cascade,
  signal_dbm integer,
  noise_dbm integer,
  frequency_mhz integer,
  channel_flags integer,
  data_rate_kbps integer,
  antenna_id integer,
  tsft bigint,
  fragment_number integer,
  channel_number integer,
  tsft_delta_us bigint,
  wall_clock_delta_ms bigint
);

do $$
begin
  if exists (
    select 1 from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'wireless_frames'
      and column_name = 'signal_dbm'
  ) then
    execute $backfill$
      insert into wireless_frame_radio (
        dedupe_key, signal_dbm, noise_dbm, frequency_mhz, channel_flags,
        data_rate_kbps, antenna_id, tsft, fragment_number, channel_number,
        tsft_delta_us, wall_clock_delta_ms
      )
      select
        dedupe_key, signal_dbm, noise_dbm, frequency_mhz, channel_flags,
        data_rate_kbps, antenna_id, tsft, fragment_number, channel_number,
        tsft_delta_us, wall_clock_delta_ms
      from wireless_frames
      on conflict (dedupe_key) do update set
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
        wall_clock_delta_ms = excluded.wall_clock_delta_ms
    $backfill$;
  end if;
end;
$$;
