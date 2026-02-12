UPDATE videos
SET master_playlist_url = 'https://d1u0dn8mhvuadb.cloudfront.net/processed/480e2b0f-1efd-4267-89db-2ff9d37293bc/tv_anime__86_eighty-six__original_soundtrack_digest__hiroyuki_sawano___kohta_yamamoto____720_x_1280__.m3u8'
WHERE id = '480e2b0f-1efd-4267-89db-2ff9d37293bc';
SELECT id, title, status, substring(master_playlist_url, 1, 80) as playlist_url FROM videos WHERE id = '480e2b0f-1efd-4267-89db-2ff9d37293bc';
