CREATE TABLE IF NOT EXISTS users
(
  id UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
  username VARCHAR(15) NOT NULL,
  email VARCHAR(255) NOT NULL,
  password CHAR(60) NOT NULL,
  phone_number VARCHAR(15) NOT NULL,
  is_deleted BOOLEAN DEFAULT FALSE NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX ON users (username);
CREATE UNIQUE INDEX ON users (email);
