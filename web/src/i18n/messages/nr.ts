import type { Messages } from "./en.js";

/**
 * DRAFT (#42). Machine-assisted isiNdebele, not yet reviewed by a
 * first-language speaker — same caveat as `android/.../res/values-nr/
 * strings.xml`, which this file's shared vocabulary is ported from, and
 * which notes this is the language with the least training material behind
 * it of the eleven. Treat every string here as a suggestion until it has
 * been reviewed.
 */
export const nr: Messages = {
  "common.settings": "Iimlungiselelo",
  "common.backToLists": "Buyela emarhelweni",
  "common.cancel": "Khansela",
  "common.close": "Vala",
  "common.delete": "Susa",
  "common.rename": "Tjhugulula ibizo",
  "common.share": "Yabelana",
  "common.edit": "Hlela",
  "common.leave": "Tjhiya",
  "common.remove": "Susa",
  "common.on": "Vulekile",
  "common.off": "Valiwe",
  "common.closeMenu": "Vala imenyu",
  "common.loading": "Kuyalayishwa…",
  "common.leaveBody": "Uzokutlhoga isimemo esitjha bona ulibone godu.",
  "common.errorRateLimited": "Iinlingo ezinengi khulu — linga godu ngemva kwesikhathi.",
  "common.errorGeneric": "Kukhona okungahambeleni kuhle. Linga godu.",

  "home.tagline": "Yifake erhelweni",
  "home.errorCreate": "Ayikghonanga ukudala irhelo. Hlola ukuxhumana kwakho bese ulinga godu.",
  "home.errorDeleteForbidden": "Mnikazi kwaphela ongasusa leli irhelo.",
  "home.errorDelete": "Ayikghonanga ukususa irhelo.",
  "home.errorLeave": "Ayikghonanga ukutjhiya irhelo. Hlola ukuxhumana kwakho bese ulinga godu.",
  "home.emptyTitle": "Akunamarhelo okwanje",
  "home.emptyBody": "Yenza irhelo lakho lokuthoma ngaphasi.",
  "home.listOptions": "Ukhetho lwerhelo",
  "home.sharedMembers": "Amalunga werhelo elabelwanwako",
  "home.colourOption": "Umbala {n}",
  "home.newListPlaceholder": "Irhelo elitjha",
  "home.add": "Ngeza",
  "home.deleteListTitle": 'Susa "{title}"?',
  "home.leaveListTitle": 'Tjhiya "{title}"?',
  "home.deleteListBody": "Lokhu kuyalisusa kibo boke abakilo. Lokhu angeke kutjhugululwe.",
  "home.untitledFallback": "leli irhelo",

  "list.backToLists": "Buyela emarhelweni",
  "list.untitled": "Irhelo elinganabizo",
  "list.errorAdd": "Ayikghonanga ukungeza umsebenzi. Hlola ukuxhumana kwakho bese ulinga godu.",
  "list.emptyBody": "Akunalitho kileli irhelo okwanje.",
  "list.markDone": 'Tjengisa "{title}" njengokwenziwe',
  "list.markNotDone": 'Tjengisa "{title}" njengokungakenziwa',
  "list.star": "Inkwenkwezi",
  "list.unstar": "Susa inkwenkwezi",
  "list.taskOptions": "Ukhetho lomsebenzi",
  "list.doneCount": "KWENZIWE ({n})",
  "list.addItemPlaceholder": "Ngeza into",
  "list.addItem": "Ngeza into",

  "signin.intro":
    "Ngena nge-imeyili yakho — sizokuthumela isixhumanisi, ayikho iphasiwedi okufuze uyikhumbule.",
  "signin.sending": "Iyathumela…",
  "signin.emailMeALink": "Ngithumele isixhumanisi",
  "signin.checkEmailTitle": "Qala i-imeyili yakho",
  "signin.delivered": "Ilethwe ku-{email}. Yivule kithi idivayisi bona uragele phambili.",
  "signin.sent":
    "Sithumele isixhumanisi sokungena ku-{email}. Kungathatha imizuzu embalwa bona ifike — yivule kithi idivayisi bona uragele phambili.",
  "signin.codeHint": "Ufunda i-imeyili kwenye indawo? Tlola ikhowudi eqiniswe kiyo lapha.",
  "signin.codePlaceholder": "Ikhowudi enamanani ali-6",
  "signin.signingIn": "Iyangena…",
  "signin.signInWithCode": "Ngena ngekhowudi",
  "signin.resendIn": "Thumela godu ngemva kwe-{s}s",
  "signin.resendLink": "Thumela isixhumanisi godu",
  "signin.useDifferentEmail": "Sebenzisa enye i-imeyili",
  "signin.errorCodeInvalid": "Leyo khowudi ayikafani. Hlola i-imeyili bese ulinga godu.",
  "signin.errorCodeExpired": "Leyo khowudi isaphele isikhathi — thumela godu bese ulinga godu.",

  "magicLink.signingIn": "Iyakungenisa…",
  "magicLink.failed":
    "Leso sixhumanisi asikasebenzi — kungenzeka sesiphelelwe sikhathi namkha sesiselatjhiwe.",
  "magicLink.backToSignIn": "Buyela ekungeneni",

  "invite.notAnInvite": "Lokho akukhambi njengesimemo.",
  "invite.wrongAccount":
    "Isimemo lesi sithunyelwe kwenye i-adresi ye-imeyili, ingasi leyo ongene ngayo.",
  "invite.expired": "Isimemo leso siphelelwe sikhathi. Bawa esitjha.",
  "invite.acceptFailed":
    "Ayikghonanga ukwamukela isimemo. Hlola ukuxhumana kwakho bese ulinga godu.",
  "invite.backToLists": "Buyela emarhelweni wakho",
  "invite.joining": "Iyajoyina…",

  "sharing.leaveTitle": "Tjhiya irhelo leli?",
  "sharing.removeTitle": "Susa {name}?",
  "sharing.removeBody": "Bazokutlhoga isimemo esitjha bona baphinde balibone leli irhelo.",
  "sharing.membersTitle": "Kwabelwana nabo",
  "sharing.checking": "Kuyatjhejwa…",
  "sharing.noMembersYet": "Akakho osele amukele isimemo seli irhelo okwanje.",
  "sharing.owner": "umnikazi",
  "sharing.you": "wena",
  "sharing.working": "Kuyasetjenzwa…",
  "sharing.shareFailedTitle": "Ayikghonanga ukwabelana",
  "sharing.shareListTitle": "Yabelana ngeli irhelo",
  "sharing.whoFor": '"{title}" yenzelwe bani?',
  "sharing.emailAddressPlaceholder": "Ikheli le-imeyili",
  "sharing.sendingInvite": 'Ithumela isimemo se-"{title}"…',
  "sharing.invitedTo": 'U-{email} umenyelwe ku-"{title}".',
  "sharing.sendInvite": "Thumela isimemo",
  "sharing.done": "Kwenziwe",

  "deleteAccount.title": "Susa i-akhawunti yakho ye-Dielys",
  "deleteAccount.sentMessage":
    "Nange u-{email} anayo i-akhawunti ye-Dielys, sithumele isixhumanisi sokuqinisekisa ukuyisusa. Isixhumanisi sisebenza imizuzu eli-15.",
  "deleteAccount.explainBody":
    "Amarhelo okukuwo wedwa ahamba nawo. Amarhelo akho anabanye aya kulowo obekade kiwo isikhathi eside — bona bahlala, wena angeke.",
  "deleteAccount.submitCta": "Ngithumele isixhumanisi sokususa",
  "deleteAccount.confirmIntro":
    "Lokhu kususa i-akhawunti yakho ngokuphelele. Angeke kutjhugululwe.",
  "deleteAccount.confirmCta": "Susa i-akhawunti yami",
  "deleteAccount.deleting": "Isusa i-akhawunti…",
  "deleteAccount.done": "I-akhawunti yakho isusiwe.",
  "deleteAccount.missingToken": "Isixhumanisi lesi asinayo i-token yaso.",
  "deleteAccount.linkExpired":
    "Isixhumanisi lesi sesiphelelwe sikhathi namkha sesiselatjhiwe. Bawa esitjha.",

  "settings.title": "Iimlungiselelo",
  "settings.account": "I-akhawunti",
  "settings.email": "I-imeyili",
  "settings.emailUnknown": "Akwaziwa",
  "settings.newItemsGoTo": "Izinto ezitjha ziya",
  "settings.top": "Phezulu",
  "settings.bottom": "Phasi",
  "settings.mobileSync": "Ukuvumelanisa kwefoni ngemuva",
  "settings.backgroundSyncDescription": "Gcina irhelo libuyekeziwe nange-app ivaliwe",
  "settings.syncLoadError": "Ayikghonanga ukulayisha isilungiselelo sokuvumelanisa ngemuva.",
  "settings.syncSaveError": "Ayikghonanga ukubulunga. Hlola ukuxhumana kwakho bese ulinga godu.",
  "settings.everyMinutes.one": "Qobe ngomzuzu {n}",
  "settings.everyMinutes.other": "Qobe ngemizuzu eli-{n}",
  "settings.everyHours.one": "Qobe ngehora {n}",
  "settings.everyHours.other": "Qobe ngamahora ali-{n}",
  "settings.language": "Ilimi",
  "settings.languageSystemDefault": "Okuzenzakalelako kwesistimu",
  "settings.privacyPolicy": "Inqubomgomo Yobumfihlo",
  "settings.signOut": "Phuma",
  "settings.deleteAccountQuestion": "Susa i-akhawunti yakho?",
  "settings.deleteAccount": "Susa i-akhawunti",
  "settings.deletingAccount": "Isusa i-akhawunti…",
  "settings.deleteAccountFailed":
    "Ayikghonanga ukususa i-akhawunti yakho: {code}. Akukho okutjhugulukileko.",
  "settings.deleteAccountNetworkError":
    "Ayikghonanga ukususa i-akhawunti yakho. Hlola ukuxhumana kwakho bese ulinga godu.",

  "play.getApp": "Fumana i-app ku-Google Play",

  "sharing.note":
    "Ngileyo ikheli le-imeyili kwaphela engakghona ukujoyina. Lisebenza amalanga alikhomba.",
  "help.title": "Isizo",
  "help.listsTitle": "Amarhelo",
  "help.listsBody":
    "Tlola ibizo ebhoksini engezansi bona wenze irhelo, bese uthinta irhelo bona ulivule. Donsa amarhelo bona utjhugulule ukulandelana kwawo — efowunini, gandelela ubambe kokuthoma. Imenyu ethi {menu} eceleni kwerhelo ngalinye iyalithiya kabutjha, itjhugulule umbala walo, yabelane ngalo namkha ilisuse. Imibala ikudivayisi yakho kwaphela; loyo owabelana naye uzikhethela okwakhe.",
  "help.itemsTitle": "Izinto",
  "help.itemsBody":
    "Tlola ebhoksini engezansi kwerhelo bona ungezelele into. Tshwaya into nayiqediwe — iya esigabeni esithi Kwenziwe, lapho ungayibuyisela khona. Faka inkwenkwezi entweni bona ihlale phezulu. Ezilungiselelweni ungakhetha bona izinto ezitjha ziya phezulu namkha phasi.",
  "help.sharingTitle": "Ukwabelana ngerhelo",
  "help.sharingBody":
    "Ngumuntu owenze irhelo kwaphela ongabelana ngalo. Vula imenyu ethi {menu}, khetha u-Yabelana bese utlola ikheli le-imeyili lomunye umuntu. Bafumana i-imeyili enesihlanganisi sesimemo. Isihlanganisi sisebenza amalanga alikhomba begodu ngileyo ikheli le-imeyili kwaphela elingasamukela, ngalokho kufuze bangene ngekheli elifanako — ewebhu namkha ku-app ye-Android.",
  "help.togetherTitle": "Ukusebenzisa irhelo elabelwanako",
  "help.togetherBody":
    "Woke umuntu osererhelweni elabelwanako angangezelela, atshwaye, ahlele, afake inkwenkwezi begodu ahlele kabutjha izinto, begodu amatjhuguluko avela kiwo woke umuntu emizuzwaneni embalwa. Isithombe sabantu eceleni kwerhelo sitjengisa bona labelwana nabani.",
  "help.leavingTitle": "Ukutjhiya nokususa",
  "help.leavingBody":
    "Nanyana ngubani angatjhiya irhelo elabelwanako ngemenyu yalo ethi {menu}, begodu umnikazi angasusa abantu ngesithombe sabantu. Ngumnikazi kwaphela ongasusa irhelo, begodu ukulisusa kulisusa kiwo woke umuntu. Nanyana ngubani otjhiyako namkha osuswako utlhoga isimemo esitjha bona abuye.",
  "help.offlineTitle": "Ngaphandle kokuhlanganiswa",
  "help.offlineBody":
    "I-Die Lys isebenza ngaphandle kokuhlanganiswa. Amatjhuguluko wakho agcinwa kudivayisi yakho begodu athunyelwa msinyana nawubuyela ku-inthanethi.",
  "help.accountTitle": "I-akhawunti yakho",
  "help.accountBody":
    "Ayikho iphasiwedi. Ngena ngekheli lakho le-imeyili begodu sikuthumelela isihlanganisi nekhodi yeenomboro ezi-6 — sebenzisa nanyana ngiyiphi elula. Ngena nge-imeyili efanako efowunini yakho neewebhu bona ubone amarhelo afanako.",
  "sharing.howItWorks": "Ukwabelana kusebenza njani",
};
