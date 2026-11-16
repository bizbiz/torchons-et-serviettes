# Additional permission under GNU AGPL version 3, section 7

Torchons & Serviettes is licensed under the GNU Affero General Public License, version 3
(see [`LICENSE`](LICENSE)). As the copyright holder, the author (GitHub: `bizbiz`) grants
the following additional permission, as allowed by section 7 of that license.

## Permission

If you modify this program, or any covered work, by linking or combining it with any of the
libraries listed below (or a modified version of those libraries), containing parts covered
by the terms of their own licenses, the licensors of this program grant you additional
permission to convey the resulting work.

Corresponding Source for a non-source form of such a combination shall not include the
source code for the parts of those libraries; it still includes the source code of this
program.

## Libraries covered

These libraries are distributed by Google under non-free terms, and the Android app uses
them for the features named here:

| Library | Used for |
|---|---|
| Google Play Billing Library (`com.android.billingclient:billing-ktx`) | optional support payment |
| Firebase Cloud Messaging (`com.google.firebase:firebase-messaging`) and the Google Play services it depends on | push notifications |
| Google ML Kit barcode scanning, bundled through `io.github.g00fy2.quickie:quickie-bundled` | scanning the QR code to join a household |

The permission covers only these libraries, and the Google Play services components they
require at runtime. It does not cover any other non-free code.

## Scope

- The backend (`backend/`) and the website (`site/`) use none of these libraries; this
  permission does not change their license.
- You may remove this permission from your own modified versions, as section 7 allows. A
  version without these libraries (for example, for F-Droid) does not need it.
