import type { Messages } from "./en.js";

/**
 * DRAFT (#42). Machine-assisted isiXhosa, not yet reviewed by a first-language
 * speaker — same caveat as `android/.../res/values-xh/strings.xml`, which
 * this file's shared vocabulary is ported from. Treat every string here as a
 * suggestion until it has been reviewed.
 */
export const xh: Messages = {
  "common.settings": "Iisetingi",
  "common.backToLists": "Buyela kuluhlu",
  "common.cancel": "Rhoxisa",
  "common.close": "Vala",
  "common.delete": "Cima",
  "common.rename": "Tshintsha igama",
  "common.share": "Yabelana",
  "common.edit": "Hlela",
  "common.leave": "Shiya",
  "common.remove": "Susa",
  "common.on": "Vuliwe",
  "common.off": "Valiwe",
  "common.closeMenu": "Vala imenyu",
  "common.loading": "Kuyalayishwa…",
  "common.leaveBody": "Uya kufuna isimemo esitsha ukuze ulubone kwakhona.",
  "common.errorRateLimited": "Imizamo emininzi kakhulu — zama kwakhona kamva.",
  "common.errorGeneric": "Kukho into engahambanga kakuhle. Zama kwakhona.",

  "home.tagline": "Yifake eluhlwini",
  "home.errorCreate":
    "Ayikwazanga ukudala uluhlu. Khangela uqhagamshelo lwakho uze uzame kwakhona.",
  "home.errorDeleteForbidden": "Ngumnini kuphela onokucima olu luhlu.",
  "home.errorDelete": "Ayikwazanga ukucima uluhlu.",
  "home.errorLeave":
    "Ayikwazanga ukushiya uluhlu. Khangela uqhagamshelo lwakho uze uzame kwakhona.",
  "home.emptyTitle": "Akukho zintlu okwangoku",
  "home.emptyBody": "Yenza uluhlu lwakho lokuqala ngezantsi.",
  "home.listOptions": "Iinketho zoluhlu",
  "home.sharedMembers": "Amalungu oluhlu olwabelwanayo",
  "home.colourOption": "Umbala {n}",
  "home.newListPlaceholder": "Uluhlu olutsha",
  "home.add": "Yongeza",
  "home.deleteListTitle": 'Cima "{title}"?',
  "home.leaveListTitle": 'Shiya "{title}"?',
  "home.deleteListBody": "Oku kuyasusa kubo bonke abakulo. Oku akunakuguqulwa.",
  "home.untitledFallback": "olu luhlu",

  "list.backToLists": "Buyela kuluhlu",
  "list.untitled": "Uluhlu olungenasihloko",
  "list.errorAdd":
    "Ayikwazanga ukongeza umsebenzi. Khangela uqhagamshelo lwakho uze uzame kwakhona.",
  "list.emptyBody": "Akukho nto kolu luhlu okwangoku.",
  "list.markDone": 'Phawula "{title}" njengegqityiwe',
  "list.markNotDone": 'Phawula "{title}" njengengekagqitywa',
  "list.star": "Inkwenkwezi",
  "list.unstar": "Susa inkwenkwezi",
  "list.taskOptions": "Iinketho zomsebenzi",
  "list.doneCount": "KUGQIBILE ({n})",
  "list.addItemPlaceholder": "Yongeza into",
  "list.addItem": "Yongeza into",

  "signin.intro":
    "Ngena nge-imeyile yakho — siza kukuthumela ikhonkco, akukho phasiwedi omele uyikhumbule.",
  "signin.sending": "Iyathumela…",
  "signin.emailMeALink": "Ndithumele ikhonkco",
  "signin.checkEmailTitle": "Jonga i-imeyile yakho",
  "signin.delivered": "Ithunyelwe ku-{email}. Yivule kwesi sixhobo ukuze uqhubeke.",
  "signin.sent":
    "Sithumele ikhonkco lokungena ku-{email}. Kunokuthatha imizuzu embalwa ukufika — yivule kwesi sixhobo ukuze uqhubeke.",
  "signin.codeHint": "Ufunda i-imeyile kwenye indawo? Chwetheza ikhowudi ekuyo apha.",
  "signin.codePlaceholder": "Ikhowudi eneenombolo ezi-6",
  "signin.signingIn": "Iyangena…",
  "signin.signInWithCode": "Ngena ngekhowudi",
  "signin.resendIn": "Thumela kwakhona kwi-{s}s",
  "signin.resendLink": "Thumela ikhonkco kwakhona",
  "signin.useDifferentEmail": "Sebenzisa enye i-imeyile",
  "signin.errorCodeInvalid": "Loo khowudi ayihambelananga. Khangela i-imeyile uze uzame kwakhona.",
  "signin.errorCodeExpired": "Loo khowudi iphelelwe lixesha — thumela kwakhona uze uzame kwakhona.",

  "magicLink.signingIn": "Iyakungenisa…",
  "magicLink.failed":
    "Elo khonkco alisebenzanga — mhlawumbi liphelelwe lixesha okanye selisetyenzisiwe.",
  "magicLink.backToSignIn": "Buyela ekungeneni",

  "invite.notAnInvite": "Oko akukhangeleki njengesimemo.",
  "invite.wrongAccount":
    "Esi simemo sithunyelwe kwenye idilesi ye-imeyile, ingeyiyo leyo ungene ngayo.",
  "invite.expired": "Eso simemo siphelelwe lixesha. Cela esitsha.",
  "invite.acceptFailed":
    "Ayikwazanga ukwamkela isimemo. Khangela uqhagamshelo lwakho uze uzame kwakhona.",
  "invite.backToLists": "Buyela kuluhlu lwakho",
  "invite.joining": "Iyajoyina…",

  "sharing.leaveTitle": "Shiya olu luhlu?",
  "sharing.removeTitle": "Susa {name}?",
  "sharing.removeBody": "Baza kufuna isimemo esitsha ukuze baphinde balubone olu luhlu.",
  "sharing.membersTitle": "Kwabelwana nabo",
  "sharing.checking": "Kuyakhangelwa…",
  "sharing.noMembersYet": "Akukho mntu wamkele isimemo kolu luhlu okwangoku.",
  "sharing.owner": "umnini",
  "sharing.you": "wena",
  "sharing.working": "Kuyasetyenzwa…",
  "sharing.shareFailedTitle": "Ayikwazanga ukwabelana",
  "sharing.shareListTitle": "Yabelana ngolu luhlu",
  "sharing.whoFor": '"{title}" yenzelwe bani?',
  "sharing.emailAddressPlaceholder": "Idilesi ye-imeyile",
  "sharing.sendingInvite": 'Ithumela isimemo se-"{title}"…',
  "sharing.invitedTo": 'U-{email} umenyelwe ku-"{title}".',
  "sharing.sendInvite": "Thumela isimemo",
  "sharing.done": "Kugqityiwe",

  "deleteAccount.title": "Cima i-akhawunti yakho ye-Dielys",
  "deleteAccount.sentMessage":
    "Ukuba u-{email} unayo i-akhawunti ye-Dielys, sithumele ikhonkco lokuqinisekisa ukuyicima. Ikhonkco lisebenza imizuzu eli-15.",
  "deleteAccount.explainBody":
    "Uluhlu okulo wedwa luyahamba nayo. Uluhlu olungolwakho olunabanye luya kulowo ebekulo ixesha elide — bona bahlala, wena akunjalo.",
  "deleteAccount.submitCta": "Ndithumele ikhonkco lokucima",
  "deleteAccount.confirmIntro": "Oku kucima i-akhawunti yakho ngokupheleleyo. Akunakuguqulwa.",
  "deleteAccount.confirmCta": "Cima i-akhawunti yam",
  "deleteAccount.deleting": "Icima i-akhawunti…",
  "deleteAccount.done": "I-akhawunti yakho icinyiwe.",
  "deleteAccount.missingToken": "Eli khonkco alinayo i-token yalo.",
  "deleteAccount.linkExpired":
    "Eli khonkco liphelelwe lixesha okanye selisetyenzisiwe. Cela elitsha.",

  "settings.title": "Iisetingi",
  "settings.account": "I-akhawunti",
  "settings.email": "I-imeyile",
  "settings.emailUnknown": "Ayaziwa",
  "settings.newItemsGoTo": "Izinto ezintsha ziya",
  "settings.top": "Phezulu",
  "settings.bottom": "Ezantsi",
  "settings.mobileSync": "Ukuvumelanisa kwefowuni ngasemva",
  "settings.backgroundSyncDescription": "Gcina uluhlu luhlaziyiwe xa usetyenziso luvaliwe",
  "settings.syncLoadError": "Ayikwazanga ukufaka isicwangciso sokuvumelanisa ngasemva.",
  "settings.syncSaveError":
    "Ayikwazanga ukugcina. Khangela uqhagamshelo lwakho uze uzame kwakhona.",
  "settings.everyMinutes.one": "Rhoqo ngomzuzu {n}",
  "settings.everyMinutes.other": "Rhoqo ngemizuzu eyi-{n}",
  "settings.everyHours.one": "Rhoqo ngeyure {n}",
  "settings.everyHours.other": "Rhoqo ngeeyure ezi-{n}",
  "settings.language": "Ulwimi",
  "settings.languageSystemDefault": "Okuzenzekelayo kwenkqubo",
  "settings.privacyPolicy": "Umgaqo-nkqubo Wemfihlo",
  "settings.signOut": "Phuma",
  "settings.deleteAccountQuestion": "Cima i-akhawunti yakho?",
  "settings.deleteAccount": "Cima i-akhawunti",
  "settings.deletingAccount": "Icima i-akhawunti…",
  "settings.deleteAccountFailed":
    "Ayikwazanga ukucima i-akhawunti yakho: {code}. Akukho nto itshintshileyo.",
  "settings.deleteAccountNetworkError":
    "Ayikwazanga ukucima i-akhawunti yakho. Khangela uqhagamshelo lwakho uze uzame kwakhona.",

  "play.getApp": "Fumana i-app kuGoogle Play",

  "sharing.note": "Yiloo dilesi ye-imeyile kuphela enokujoyina. Isebenza iintsuku ezisixhenxe.",
  "help.title": "Uncedo",
  "help.listsTitle": "Uluhlu",
  "help.listsBody":
    "Bhala igama kwibhokisi elingezantsi ukuze wenze uluhlu, uze ucofe uluhlu ukuze uluvule. Tsala uluhlu ukuze utshintshe ulandelelwano — kwifowuni, cofa uze ubambe kuqala. Imenyu ethi {menu} ecaleni koluhlu ngalunye iyaluthiya ngokutsha, itshintshe umbala walo, yabelane ngalo okanye iyalucima. Imibala ikwisixhobo sakho kuphela; nabani na owabelana naye uzikhethela ezakhe.",
  "help.itemsTitle": "Izinto",
  "help.itemsBody":
    "Bhala kwibhokisi elingezantsi koluhlu ukuze wongeze into. Phawula into xa igqityiwe — iya kwicandelo elithi Kugqibile, apho unokuyibuyisela khona. Faka inkwenkwezi entweni ukuze ihlale phezulu. KuSeto ungakhetha ukuba izinto ezintsha ziya phezulu okanye ezantsi.",
  "help.sharingTitle": "Ukwabelana ngoluhlu",
  "help.sharingBody":
    "Ngumntu owenze uluhlu kuphela onokwabelana ngalo. Vula imenyu ethi {menu}, ukhethe u-Yabelana uze ubhale idilesi ye-imeyile yomnye umntu. Bafumana i-imeyile enekhonkco lesimemo. Ikhonkco lisebenza iintsuku ezisixhenxe kwaye yiloo dilesi ye-imeyile kuphela enokulamkela, ngoko kufuneka bangene ngedilesi efanayo — kwiwebhu okanye kwi-app ye-Android.",
  "help.togetherTitle": "Ukusebenzisa uluhlu olwabelwanayo",
  "help.togetherBody":
    "Wonke umntu okuluhlu olwabelwanayo unokongeza, aphawule, ahlele, afake inkwenkwezi aze alungelelanise ngokutsha izinto, kwaye utshintsho luvela kuwo wonke umntu kwimizuzwana embalwa. Umfanekiso wabantu ecaleni koluhlu ubonisa ukuba lwabelwana nabani.",
  "help.leavingTitle": "Ukushiya nokucima",
  "help.leavingBody":
    "Nabani na unokushiya uluhlu olwabelwanayo ngemenyu yalo ethi {menu}, kwaye umnini unokususa abantu ngomfanekiso wabantu. Ngumnini kuphela onokucima uluhlu, kwaye ukulucima kulususa kuwo wonke umntu. Nabani na oshiyayo okanye osuswayo ufuna isimemo esitsha ukuze abuye.",
  "help.offlineTitle": "Ngaphandle konxibelelwano",
  "help.offlineBody":
    "I-Die Lys isebenza ngaphandle konxibelelwano. Utshintsho lwakho lugcinwa kwisixhobo sakho kwaye luthunyelwa nje ukuba ubuyele kwi-intanethi.",
  "help.accountTitle": "Iakhawunti yakho",
  "help.accountBody":
    "Akukho phaswedi. Ngena ngedilesi yakho ye-imeyile kwaye siya kukuthumela ikhonkco nekhowudi yamanani ama-6 — sebenzisa nokuba yeyiphi elula. Ngena nge-imeyile efanayo kwifowuni yakho nakwiwebhu ukuze ubone uluhlu olufanayo.",
  "sharing.howItWorks": "Indlela ukwabelana okusebenza ngayo",
};
