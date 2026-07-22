-- object: wireless_frame_qos
-- folder: tables
-- depends_on: wireless_frames
create table if not exists wireless_frame_qos (
  dedupe_key text primary key references wireless_frames(dedupe_key) on delete cascade,
  qos_tid integer,
  qos_eosp boolean,
  qos_ack_policy integer,
  qos_ack_policy_label text,
  qos_amsdu boolean,
  more_data boolean not null default false,
  retry boolean not null default false,
  power_save boolean not null default false,
  protected boolean not null default false,
  frame_control_flags integer not null default 0
);

do $$
begin
  if exists (
    select 1 from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'wireless_frames'
      and column_name = 'qos_tid'
  ) then
    execute $backfill$
      insert into wireless_frame_qos (
        dedupe_key, qos_tid, qos_eosp, qos_ack_policy, qos_ack_policy_label,
        qos_amsdu, more_data, retry, power_save, protected, frame_control_flags
      )
      select
        dedupe_key, qos_tid, qos_eosp, qos_ack_policy, qos_ack_policy_label,
        qos_amsdu, more_data, retry, power_save, protected, frame_control_flags
      from wireless_frames
      on conflict (dedupe_key) do update set
        qos_tid = excluded.qos_tid,
        qos_eosp = excluded.qos_eosp,
        qos_ack_policy = excluded.qos_ack_policy,
        qos_ack_policy_label = excluded.qos_ack_policy_label,
        qos_amsdu = excluded.qos_amsdu,
        more_data = excluded.more_data,
        retry = excluded.retry,
        power_save = excluded.power_save,
        protected = excluded.protected,
        frame_control_flags = excluded.frame_control_flags
    $backfill$;
  end if;
end;
$$;
