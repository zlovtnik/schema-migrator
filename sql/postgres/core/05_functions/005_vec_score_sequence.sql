-- object: vec_score_sequence
-- folder: functions
-- depends_on: vec_transition_model
-- Score a sequence of tokens using the Laplace-smoothed bigram log-probability.
-- Tokens are passed as a text array. Returns the sum of log2( P(next|prev) )
-- where P(next|prev) = (count(prev, next) + 1) / (total_from(prev) + vocab_size).
-- Sequences shorter than 2 tokens return 0 (no score).
create or replace function vec_score_sequence(p_tokens text[])
returns double precision
language plpgsql
as $$
declare
  v_log_prob double precision := 0.0;
  v_total bigint;
  v_vocab_size bigint;
  v_prev text;
  v_next text;
  v_count bigint;
  v_prob double precision;
begin
  if array_length(p_tokens, 1) < 2 then
    return 0.0;
  end if;

  -- Compute vocabulary size (distinct tokens seen in either position)
  select count(distinct token)::bigint into v_vocab_size
  from (
    select prev_token as token from vec_transition_model where embedding_kind = 'frame_sequence'
    union
    select next_token as token from vec_transition_model where embedding_kind = 'frame_sequence'
  ) vocab;

  -- Fallback: if no model exists, use uniform probability over a default vocab of 16
  if v_vocab_size is null or v_vocab_size = 0 then
    v_vocab_size := 16;
  end if;

  for i in 2 .. array_upper(p_tokens, 1) loop
    v_prev := p_tokens[i - 1];
    v_next := p_tokens[i];

    -- Count of this specific bigram
    select count into v_count
    from vec_transition_model
    where prev_token = v_prev
      and next_token = v_next
      and embedding_kind = 'frame_sequence';

    if not found then
      v_count := 0;
    end if;

    -- Total count of all bigrams starting with v_prev
    select coalesce(sum(count), 0)::bigint into v_total
    from vec_transition_model
    where prev_token = v_prev
      and embedding_kind = 'frame_sequence';

    if v_total is null or v_total = 0 then
      -- Unknown prefix: use Laplace-smooth uniform across vocab
      v_prob := 1.0 / v_vocab_size;
    else
      v_prob := (v_count + 1.0) / (v_total + v_vocab_size);
    end if;

    v_log_prob := v_log_prob + (ln(v_prob) / ln(2.0));
  end loop;

  return v_log_prob;
end;
$$;
