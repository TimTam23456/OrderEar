# Order Ear

A voice order taker prototype. Tap the mic and read out an order; it builds a ticket from the full Australian menu (163 items, sizes, meals, sauces, coffee milk and sugar, changes and removals).

It's a standalone demo: it does not connect to any till or POS system.

## Getting the APK
Every push to `main` builds the app on GitHub Actions and publishes **OrderEar.apk** under **Releases**. Open it on your phone to install (allow installs from your browser if asked). New builds install over the old one.

## Files
- `app/src/main/assets/index.html`: the order-taking page (menu, order parser, ticket)
- `app/src/main/java/.../MainActivity.kt`: wraps the page and feeds it Android's speech recognition
