# Third-party notices

## QQ Chat Exporter

- Project: <https://github.com/shuakami/qq-chat-exporter>
- Pinned integration revision: `58fc533f1bfdbf4535be0f83af4e595d4d7ecf27`
- License: GNU General Public License v3.0
- Integration: QQ Chat Exporter/NapCat runs as a separately built GPL component behind a restricted internal bridge. Its source is not copied into or linked with the Spring/Vue customer-cloud application.
- Build-time modifications: the deterministic patch in `deploy/qce-patches/apply.mjs` changes the WebUI base path, replaces QCE token login with the customer-cloud Session/CSRF adapter, routes HTTP/WebSocket through the allowlisted gateway, adds ranked 300 ms session search and the explicit “同步到客户云” action, and removes token-bearing download links.
- License and modification notice inside the image: `/usr/share/licenses/qq-chat-exporter/LICENSE` and `/usr/share/licenses/qq-chat-exporter/YINGGE-MODIFICATIONS.md`.
- Corresponding-source bundle: set `QCE_SOURCE_ARCHIVE_DIR` when running `deploy/scripts/build-qce-image.sh`; the build stores the pinned, patched source tree as `qq-chat-exporter-58fc533f1bfdbf4535be0f83af4e595d4d7ecf27-yingge-source.tar.gz` outside the application image.

Deployments that build or distribute the pinned image must retain the upstream license and provide both the corresponding source at the revision above and the Yingge build-time patch source. The GPL component remains operationally and build-wise separate from proprietary customer-cloud modules.
