-- object: vec_rf_sensor_locations
-- folder: tables
-- depends_on: -
create table if not exists vec_rf_sensor_locations (
  sensor_id text not null,
  location_id text not null default '',
  latitude double precision not null,
  longitude double precision not null,
  site_label text,
  enabled boolean not null default true,
  updated_at timestamptz not null default now(),
  constraint vec_rf_sensor_locations_pk primary key (sensor_id, location_id),
  constraint vec_rf_sensor_locations_latitude_chk check (latitude between -90 and 90),
  constraint vec_rf_sensor_locations_longitude_chk check (longitude between -180 and 180)
);
