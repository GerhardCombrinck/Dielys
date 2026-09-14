# Google Play Console answers

Ready-to-paste answers for every **App content** section and the store listing, written against
what the app actually does as of v0.4.0. When the app changes — a new SDK, a new kind of data,
ads, a new permission — update this file in the same change, then update the Console.

Console path for most of this: **Policy and programmes → App content** (also reachable from the
setup checklist on the app's Dashboard).

| | |
|---|---|
| Developer | Invisionsoft (Pty) Ltd (organisation account, D-U-N-S verified) |
| Package | `za.co.dielys` |
| Contact email | support@invisionsoft.co.za |
| Website | https://dielys.com |
| Privacy policy | https://dielys.com/privacy |
| Account deletion | https://dielys.com/account/delete |

---

## Privacy policy

**URL:** `https://dielys.com/privacy`

## App access

**All or some functionality is restricted** → **Add instructions**:

- **Name:** Review account
- **Username / email:** the value of the prod `REVIEW_EMAIL` secret
- **Password:** the value of the prod `REVIEW_CODE` secret (digits only, at least 12)
- **Any other information required to access your app:**

  > Die Lys signs in without a password. To sign in as the review account:
  > 1. Enter the email address above and tap **Email me a link**.
  > 2. On the **Check your email** screen, type the password above into the **Code** field and
  >    tap **Sign in with code**. (No email is sent to this address; the code works on its own.)
  >
  > The account starts with no lists. Tap **+** to create one and add items. To try sharing,
  > open a list's share option and invite any other email address.
  >
  > The language can be changed under Settings.

Tick **no other information needed** only if Google offers it — the steps above are required.

## Ads

**No, my app does not contain ads.**

## Content rating

Start a new questionnaire.

- **Email:** support@invisionsoft.co.za
- **Category:** *All Other App Types* (utility, productivity, communication)
- **Violence, sexuality, language, controlled substances, gambling, horror:** No to every question.
- **Does the app allow users to interact or exchange content with each other?** **Yes** — people
  share a list with others they invite by email, and everyone on it sees what the others add.
- **Is shared, user-generated content the primary source of content in the app?** **No** — a
  person's lists are private to them unless the owner invites specific people by email; there
  is no feed, public profile or discovery. (Yes would bring in the UGC policy's expectations of
  moderation and in-app reporting and blocking, which fit a social platform, not a shared
  shopping list.)
- Follow-ups to "users interact":
  - **Public sharing of nudity / real-world graphic violence?** No / No — nothing is public, and
    lists are text only.
  - **Ability to block users or user-generated content?** No — an owner can remove a member, but
    there is no block feature.
  - **Ability to report users or user-generated content?** No
  - **Chat moderation?** No — there is no chat.
  - **Can interactions be limited to invited friends only?** **Yes** — they always are: a list is
    only ever shared with people its owner invites by email.
- **Does the app share the user's current physical location with other users?** No
- **Does the app allow users to purchase digital goods?** No
- **Is the app a web browser or search engine?** No
- **Does the app contain unrestricted internet access?** No

Expected result: Everyone / PEGI 3 or similar, with a "Users Interact" descriptor.

## Target audience and content

- **Target age groups:** 13–15, 16–17, 18 and over. Not under 13 — the privacy policy says the app
  is not aimed at children under 13, and ticking a younger group brings in the Families policy.
- **Could the app unintentionally appeal to children?** No.

## News app

**No.**

## Health apps / Financial features / Government apps

**None** / **My app doesn't provide any financial features** / **No.**

## Advertising ID

**No** — the app does not use the advertising ID. (Checked: the merged release manifest has no
`com.google.android.gms.permission.AD_ID`, and no analytics or ads SDK is linked. Only
`firebase-messaging` is.)

## Data safety

### Overview

- **Does your app collect or share any of the required user data types?** Yes
- **Is all of the user data collected by your app encrypted in transit?** Yes (HTTPS and WSS only)
- **Which of the following methods of account creation does your app support?**
  *Username and other authentication* → email address with a sign-in link or code (no password).
- **Do you provide a way for users to request that their data is deleted?** Yes
- **Delete account URL:** `https://dielys.com/account/delete`

### Data types

Declare **collected**, never **shared**. Cloudflare (hosting), Brevo (email) and Google (push)
process data only on our behalf, which Play counts as service providers, not sharing.

| Section | Data type | Collected | Required or optional | Processed ephemerally | Purposes |
|---|---|---|---|---|---|
| Personal info | **Email address** | Yes | Required | No | App functionality, Account management |
| Personal info | **User IDs** (the account ID the server assigns) | Yes | Required | No | App functionality, Account management |
| App activity | **Other user-generated content** (list names and items) | Yes | Required | No | App functionality |
| Device or other IDs | **Device or other IDs** (random app-generated device ID; FCM registration token) | Yes | Required | No | App functionality |

Everything else: **not collected**. In particular:

- **Location:** not collected. IP addresses are seen by the hosting provider and turned into a
  keyed hash for rate limiting that is kept about a day; nothing is derived from them.
- **App info and performance** (crash logs, diagnostics): not collected — no crash reporting SDK.
- **Contacts:** not collected — the app never reads the address book. An email address typed to
  invite someone is used to send that one invitation and not kept afterwards.
- **Financial info, health, messages, photos, audio, files, calendar, web history:** not
  collected.

## Account deletion (Data deletion)

- **Delete account URL:** `https://dielys.com/account/delete`
- **Can users request deletion of some data without deleting their account?** Yes — a person can
  leave a shared list or delete lists they own in the app. (Answer No if the Console's wording
  implies a separate request form; the page above covers the account.)

## Permissions declarations

No declaration forms should be needed:

- `POST_NOTIFICATIONS`, `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`,
  `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` (the last three from WorkManager) are normal
  permissions.
- No `FOREGROUND_SERVICE_*` type permission, no location, no SMS or call log, no
  `QUERY_ALL_PACKAGES`, no exact alarms.

If the Console asks for a foreground service declaration anyway, answer that the app declares no
foreground service type of its own; the base permission comes from AndroidX WorkManager.

---

## Store listing

**Grow → Store presence → Main store listing.** Default language en-GB, with an Afrikaans (af)
translation added under *Manage translations*.

- **App category:** Productivity
- **Tags:** Productivity, Notebook, Shopping, House & home, Tools (Play's fixed tag list has no
  to-do or shopping-list tag)
- **Contact:** support@invisionsoft.co.za, website https://dielys.com

### English (en-GB)

**App name** (30 max)

```
Die Lys: Sit dit op die lys
```

**Short description** (80 max)

```
Shared lists for your household: groceries, chores and more. Works offline.
```

**Full description** (4000 max)

```
Die Lys is a simple list app for the people you live with.

Make a list for the groceries, the chores, or what to pack for the weekend, and share it with the people who need it. When someone adds or ticks off an item, everyone on the list sees it straight away.

• Shared lists — invite someone by email, and you are both on the same list
• Instant sync — changes appear on every phone as they happen
• Works offline — add and tick items in the shop with no signal; they sync when you are back
• Star what matters — starred items stay at the top
• Drag to reorder, and give each list its own colour
• No passwords — sign in with a link or code sent to your email
• Eleven South African languages, including Afrikaans, isiZulu, isiXhosa and Sesotho

No ads. No tracking. Your lists are yours, and you can delete your account at any time.

Die Lys is made in South Africa by Invisionsoft.
```

### Afrikaans (af)

**App name**

```
Die Lys: Sit dit op die lys
```

**Short description**

```
Gedeelde lyste vir jou huishouding: inkopies, takies en meer. Werk ook aflyn.
```

**Full description**

```
Die Lys is 'n eenvoudige lys-toep vir die mense saam met wie jy woon.

Maak 'n lys vir die inkopies, die takies, of wat om vir die naweek in te pak, en deel dit met die mense wat dit nodig het. Wanneer iemand iets byvoeg of afmerk, sien almal op die lys dit dadelik.

• Gedeelde lyste — nooi iemand per e-pos, en julle is albei op dieselfde lys
• Dadelike sinkronisering — veranderinge verskyn op elke foon soos dit gebeur
• Werk aflyn — voeg by en merk af in die winkel sonder sein; dit sinkroniseer wanneer jy weer verbinding het
• Sterre vir wat saak maak — items met 'n ster bly bo
• Sleep om die volgorde te verander, en gee elke lys sy eie kleur
• Geen wagwoorde nie — meld aan met 'n skakel of kode wat na jou e-pos gestuur word
• Elf Suid-Afrikaanse tale, insluitend Afrikaans, isiZulu, isiXhosa en Sesotho

Geen advertensies nie. Geen naspeuring nie. Jou lyste is joune, en jy kan jou rekening enige tyd skrap.

Die Lys word in Suid-Afrika gemaak deur Invisionsoft.
```

### Graphics

In [`graphics/`](graphics/): `make_graphics.py` draws the icon and both feature graphics,
`frame_screenshots.py` frames phone captures into the `screenshot-<lang>-N.png` set. None of
it is AI-generated imagery, so the store listing's AI asset declaration is **Don't label assets**.

| Asset | Size | Notes |
|---|---|---|
| App icon | 512 × 512 PNG, 32-bit, no transparency | The navy "D" mark, full-bleed; Play applies its own rounding |
| Feature graphic | 1024 × 500 PNG or JPEG | Navy ground, the mark, "Sit dit op die lys" |
| Phone screenshots | 2–8, 16:9 or 9:16, 320–3840 px per side | Lists screen, a list with starred and done items, sharing sheet, sign-in with code |

Screenshots can be taken from a debug build on the phone over Wi-Fi ADB
(`adb exec-out screencap -p > shot.png`) — use a demo account with realistic Afrikaans and
English list names, never a real household's lists.

---

## Release notes

### 0.4.0 (first Play release)

en-GB:

```
First release on Google Play.
• Sign in with a code from your email, as well as the link
• Delete your account from Settings or at dielys.com/account/delete
```

af:

```
Eerste vrystelling op Google Play.
• Meld aan met 'n kode uit jou e-pos, sowel as die skakel
• Skrap jou rekening in Instellings of by dielys.com/account/delete
```
