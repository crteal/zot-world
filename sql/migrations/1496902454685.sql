CREATE TABLE IF NOT EXISTS sites
(
  id UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
  owner_id UUID NOT NULL REFERENCES users (id),
  slug TEXT UNIQUE NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  likes UUID[],
  metadata JSONB,
  phone_number VARCHAR(15) UNIQUE NOT NULL,
  twilio_account JSONB NOT NULL,
  is_public BOOLEAN DEFAULT FALSE NOT NULL,
  is_enabled BOOLEAN DEFAULT TRUE NOT NULL,
  is_deleted BOOLEAN DEFAULT FALSE NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ
);

CREATE OR REPLACE VIEW sites_view AS
SELECT
  s.id,
  s.owner_id,
  u.username AS owner_username,
  u.phone_number AS owner_phone_number,
  s.slug,
  s.title,
  s.description,
  ARRAY(
    SELECT
      row_to_json(
        (SELECT
          d
        FROM (SELECT id, username) d)
      )
    FROM users
    WHERE id = ANY(s.likes)
  ) AS likes,
  s.metadata,
  s.phone_number,
  s.twilio_account,
  s.is_public,
  s.is_enabled,
  s.created_at,
  s.updated_at
FROM sites s
JOIN users u
ON s.owner_id = u.id
AND u.is_deleted IS FALSE
WHERE s.is_deleted IS FALSE;

CREATE INDEX posts_created_at_idx ON posts (created_at);

ALTER TABLE posts ADD COLUMN is_deleted BOOLEAN DEFAULT FALSE NOT NULL;

ALTER TABLE posts ADD COLUMN author_id UUID;
ALTER TABLE posts ADD CONSTRAINT posts_author_id_fkey FOREIGN KEY (author_id) REFERENCES users (id);

ALTER TABLE posts ADD COLUMN site_id UUID;
ALTER TABLE posts ADD CONSTRAINT posts_site_id_fkey FOREIGN KEY (site_id) REFERENCES sites (id);

ALTER TABLE posts ADD COLUMN slug TEXT;
ALTER TABLE posts ADD CONSTRAINT posts_site_id_slug_idx UNIQUE (site_id, slug);

DROP VIEW IF EXISTS posts_view;

CREATE OR REPLACE VIEW posts_view AS
SELECT
  p.id,
  p.site_id,
  sv.phone_number AS site_phone_number,
  p.slug,
  p.author_id,
  u.username AS author_username,
  u.phone_number AS author_phone_number,
  p.data,
  ARRAY(
    SELECT
      row_to_json(
        (SELECT
          d
        FROM (SELECT id, username) d)
      )
    FROM users
    WHERE id = ANY(p.likes)
  ) AS likes,
  ARRAY(
    SELECT
      row_to_json(comments_view)
    FROM comments_view
    WHERE post_id = p.id
  ) AS comments,
  p.created_at,
  p.updated_at
FROM posts p
JOIN users u
ON p.author_id = u.id
AND u.is_deleted IS FALSE
JOIN sites_view sv
ON p.site_id = sv.id
WHERE p.is_deleted IS FALSE
ORDER BY created_at DESC;

CREATE TYPE permissions AS ENUM ('admin', 'write', 'read');

CREATE TABLE IF NOT EXISTS members
(
  id UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
  site_id UUID NOT NULL REFERENCES sites (id),
  user_id UUID NOT NULL REFERENCES users (id),
  permission permissions NOT NULL,
  is_deleted BOOLEAN DEFAULT FALSE NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ,
  UNIQUE (site_id, user_id, is_deleted)
);

CREATE OR REPLACE VIEW members_view AS
SELECT
  m.id,
  m.site_id,
  m.user_id,
  u.username AS user_username,
  u.phone_number AS user_phone_number,
  m.permission,
  m.created_at,
  m.updated_at
FROM members m
JOIN users u
ON m.user_id = u.id
AND u.is_deleted IS FALSE
WHERE m.is_deleted IS FALSE;
