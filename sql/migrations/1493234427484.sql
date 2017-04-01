CREATE TABLE IF NOT EXISTS comments
(
  id UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
  parent_id UUID REFERENCES comments (id),
  post_id UUID NOT NULL REFERENCES posts (id),
  author_id UUID NOT NULL REFERENCES users (id),
  body TEXT NOT NULL,
  likes UUID[],
  is_deleted BOOLEAN DEFAULT FALSE NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ
);

CREATE OR REPLACE VIEW comments_view AS
SELECT
  c.id,
  c.parent_id,
  c.post_id,
  c.author_id,
  u.username AS author_username,
  c.body,
  ARRAY(
    SELECT
      row_to_json(
        (SELECT
          d
        FROM (SELECT id, username) d)
      )
    FROM users
    WHERE id = ANY(c.likes)
  ) AS likes,
  c.created_at,
  c.updated_at
FROM comments c
JOIN users u
ON c.author_id = u.id
AND u.is_deleted IS FALSE
WHERE c.is_deleted IS FALSE
ORDER BY created_at ASC;

CREATE OR REPLACE VIEW posts_view AS
SELECT
  p.id,
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
ORDER BY created_at DESC;
