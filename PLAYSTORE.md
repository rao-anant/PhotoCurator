# Play Store Submission Notes — Media Curator

## App identity
| Field | Value |
|---|---|
| App name | Media Curator |
| Application ID | `com.anant.mediacurator` |
| Keystore file | `mediacurator.keystore` (gitignored — keep a backup!) |
| Keystore alias | `mediacurator` |
| Passwords | In `keystore.properties` (gitignored) |
| Privacy policy URL | `https://rao-anant.github.io/MediaCurator/privacy-policy.html` |

> ⚠️ The keystore file and passwords are gitignored. Back them up to an external drive or
> cloud storage. Losing the keystore means you can never push an update to this app on Play Store.

---

## GitHub Pages — enable the privacy policy URL
1. Go to https://github.com/rao-anant/MediaCurator
2. Settings → Pages → Source: **Deploy from a branch**
3. Branch: `main` · Folder: `/docs`
4. Save → wait ~2 min → verify at the URL above

---

## MANAGE_EXTERNAL_STORAGE declaration
When Play Store asks "why does your app need All Files Access?", paste this:

> Media Curator is a media curation app: it browses and deletes photos, videos, and PDF
> documents. All Files Access is used solely to locate PDF files stored in Downloads and
> Documents folders on the device. Android's granular media permissions
> (READ_MEDIA_IMAGES, READ_MEDIA_VIDEO) cover only photos and videos — no equivalent
> permission exists for PDF or document files on Android 13 and above. No files are
> uploaded, shared, or transmitted. The app has no internet access.
>
> (473 chars — Play Console limit is 500)

Note: Google Files, Samsung My Files, and every mainstream file manager use this same
permission. It is the canonical use case in Google's own policy documentation.

---

## Store listing — Short description (≤ 80 chars)
```
Turbocharge curating your photos, videos and PDFs, and enjoy the time reclaimed.
```

## Store listing — Full description
```
Media Curator helps you take control of your photo library — without any cloud, accounts,
or privacy trade-offs. Everything runs locally on your device.

THE PROBLEM WITH EVERY OTHER GALLERY APP
Most gallery and file manager apps let you delete what you don't want — but they offer no
way to track your progress. Come back a week later and you're back to square one, slogging
through months you've already reviewed. Media Curator solves this: mark a month as done
and it stays out of your way. Next session, you pick up exactly where you left off.
That's the difference — what used to drag across many interrupted sessions
now moves multiple times faster.

Note: deleted items are permanently removed from the phone, but hidden months
are never deleted — you can unhide them back into the app at any time.
Hidden items remain fully accessible in your phone's default Gallery app.

BROWSE YOUR LIBRARY YOUR WAY
• See all your photos, videos, and PDFs grouped by month and year
• Filter to show only photos, only videos, or only PDFs at a glance
• Sort by newest, oldest, or largest file first
• "Largest First" flat view instantly surfaces your biggest files so you can free up space fast

CURATE WITH CONFIDENCE
• Swipe through items in a full-screen viewer
• Long-press to select multiple items, then delete them all at once
• The combined size of selected items is shown before you confirm — no surprises
• Mark months as "done" once you've reviewed them, so you can track your progress

FIND STORAGE HOGS INSTANTLY
• File size is shown on every thumbnail
• Video duration is shown alongside file size
• Sort by largest file to see exactly what's eating your storage

PDF SUPPORT
• Browse PDFs stored in Downloads, Documents, and other accessible folders
• View, select, and delete PDFs alongside your photos and videos

PRIVACY FIRST
• No internet connection — ever
• No accounts, no sign-in
• No analytics, no ads, no crash-reporting SDKs
• Your media never leaves your device
• The only file the app writes is a small local backup (mediacurator_hidden.json) that
  remembers which months you've marked as reviewed — no photos, no personal data

PERMISSIONS EXPLAINED
• Photos/Videos access: to display your library
• All files access (Android 11+): to browse PDF files in Downloads and Documents folders
• Optional "One-Click Delete" (MANAGE_MEDIA on Android 12+): skip the per-operation
  confirmation dialog when deleting media. Enable/disable in the app menu at any time.

Perfect for anyone who wants to periodically clean up their camera roll, free up storage
space, or keep track of which months they've already reviewed — without handing their
photos to a third-party service.
```

---

## Content rating questionnaire
Answer **No** to every question:
- Violence? No
- Sexual content? No
- User-generated content / social features? No
- Shares location? No
- Targets children under 13? No

→ Final rating: **Everyone**

---

## Data safety section
| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all user data encrypted in transit? | N/A (no network) |
| Do users have a way to request data deletion? | **No** (no data collected) |

---

## Store assets checklist
- [ ] App icon — 512×512 PNG, no alpha, no rounded corners (Play Console adds them)
- [ ] Feature graphic — 1024×500 PNG or JPG
- [ ] Phone screenshots — minimum 2, at least 320 px wide
- [ ] Short description ✓ (above)
- [ ] Full description ✓ (above)
- [ ] Privacy policy URL ✓ (above — but must be live on GitHub Pages first)
- [ ] MANAGE_EXTERNAL_STORAGE declaration ✓ (above)

---

## Release build steps
1. Fill in `keystore.properties` with real passwords (if still using placeholder)
2. In Android Studio: Build → Generate Signed Bundle/APK → **Android App Bundle (.aab)**
3. Use the `release` build type
4. Upload the `.aab` to Play Console → Production (or Internal Testing first)
