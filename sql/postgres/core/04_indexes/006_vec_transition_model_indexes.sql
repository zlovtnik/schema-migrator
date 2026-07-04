-- object: vec_transition_model indexes
-- folder: indexes
-- depends_on: vec_transition_model
create index if not exists vec_transition_model_prev_idx
  on vec_transition_model (prev_token, embedding_kind);
