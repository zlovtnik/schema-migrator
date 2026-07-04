-- object: vec_transition_model
-- folder: tables
-- depends_on: vec_frame_sequences
-- Track 5.1: Bigram transition model for sequence scoring
create table if not exists vec_transition_model (
  id bigserial primary key,
  prev_token text not null,
  next_token text not null,
  embedding_kind text not null default 'frame_sequence',
  count bigint not null default 0,
  last_updated timestamptz not null default now(),
  constraint vec_transition_model_unique unique (prev_token, next_token, embedding_kind)
);
