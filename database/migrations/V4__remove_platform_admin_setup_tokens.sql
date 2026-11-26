-- Platform initialization now uses a loopback-restricted first-run form; setup tokens are obsolete.
DROP TABLE IF EXISTS iam.platform_admin_setup_token;
