CREATE TABLE IF NOT EXISTS posts
(
  id UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
  data JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ
);
