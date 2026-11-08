# Google Play Data safety answers

Draft answers for the Data safety form. Verify each one in the console before submitting.

## Summary
| Question | Answer |
|---|---|
| Does the app collect or share any of the required user data types? | No |
| Data collected | None |
| Data shared | None |
| Account required / account creation | No |
| Ads | No |
| Analytics or crash reporting SDKs | None |
| Third-party SDKs that collect data | None |

## Details
- The app has no backend. It never contacts a remote server.
- Music files are read from folders the user selects through the system folder picker. They stay on the device, except when the user syncs them to their own watch.
- Pairing data, settings and the library index are stored only in app-private storage. Auto-backup is disabled (`allowBackup="false"`).
- The Garmin Connect IQ Mobile SDK is a local integration: it exchanges messages with the Garmin Connect Mobile app on the same phone. PNTome does not send data to Garmin. Garmin Connect Mobile has its own privacy policy. Check this wording in the console.

## Data in transit (important, be honest)
Audio files and control messages travel from the phone to the watch as plain HTTP on the user's local network (Wi-Fi or phone hotspot). Requests are authenticated with a per-pairing shared secret, but the traffic is NOT encrypted in transit.

- Do NOT answer "Yes" to "Is all of the user data collected by your app encrypted in transit?". Since no data is collected, that question normally does not apply, but never claim encryption for the local transfer.
- `PRIVACY.md` states the same limitation.

## Data deletion
All data is local. Uninstalling the app removes it. No deletion request mechanism or web form is needed. The user can also remove synced files from the watch.

## Security practices section
- Encrypted in transit: do not claim (see above).
- Users can request data deletion: not applicable, no data is collected.
