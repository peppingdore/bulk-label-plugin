# Bulk Label Plugin

Confluence plugin for renaming labels in bulk across pages/blog posts.

## Build & Deploy

```bash
# Build locally
atlas-mvn package -q -DskipTests

# Deploy to test server
ssh root@90.156.229.235
cd /root/bulk-label-plugin
git pull origin master
/usr/share/atlassian-plugin-sdk-8.2.8/apache-maven-3.5.4/bin/mvn package -q -DskipTests
bash /root/install-plugin.sh   # installs via UPM REST API
```

## Test Server

- **Host**: root@90.156.229.235
- **Confluence (atlas-debug)**: http://90.156.229.235:1990/confluence
- **Credentials**: admin / admin
- **Screen session**: `screen -r confluence` to attach
- **Jira** is also running on this server — do not interfere with it
- **Repo clone on server**: /root/bulk-label-plugin
- **Install script**: /root/install-plugin.sh (gets UPM token from header, uploads JAR via multipart form)

## Architecture Notes

- XWork actions are instantiated by the host framework, NOT the plugin's Spring context
- Plugin-scoped Spring beans (`@Component`) are NOT visible to XWork setter injection
- Use managers from `ConfluenceActionSupport` directly (protected fields: `labelManager`, `permissionManager`; setter-injected: `spaceManager`)
- `$space` is reserved in Confluence Velocity context — do not use as a loop variable
