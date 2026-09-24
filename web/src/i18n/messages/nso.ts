import type { Messages } from "./en.js";

/**
 * DRAFT (#42). Machine-assisted Sepedi (Northern Sotho), not yet reviewed by
 * a first-language speaker — same caveat as `android/.../res/values-b+nso/
 * strings.xml`, which this file's shared vocabulary is ported from. Treat
 * every string here as a suggestion until it has been reviewed.
 */
export const nso: Messages = {
  "common.settings": "Dipeakanyo",
  "common.backToLists": "Boela mananeong",
  "common.cancel": "Khansela",
  "common.close": "Tswalela",
  "common.delete": "Phumola",
  "common.rename": "Fetola leina",
  "common.share": "Abelana",
  "common.edit": "Fetola",
  "common.leave": "Tlogela",
  "common.remove": "Tloša",
  "common.on": "E Bulegile",
  "common.off": "E Tswaletšwe",
  "common.closeMenu": "Tswalela lenaneo",
  "common.loading": "E a lodiwa…",
  "common.leaveBody": "O tla hloka taletšo ye mpsha gore o le bone gape.",
  "common.errorRateLimited": "Maiteko a mantši kudu — leka gape ka morago.",
  "common.errorGeneric": "Go na le seo se sa lokago. Leka gape.",

  "home.tagline": "E bee lenaneong",
  "home.errorCreate": "E paletšwe go hlama lenaneo. Lekola kgokagano ya gago gomme o leke gape.",
  "home.errorDeleteForbidden": "Ke mong feela yo a ka phumolago lenaneo le.",
  "home.errorDelete": "E paletšwe go phumola lenaneo.",
  "home.errorLeave": "E paletšwe go tlogela lenaneo. Lekola kgokagano ya gago gomme o leke gape.",
  "home.emptyTitle": "Ga go mananeo gabjale",
  "home.emptyBody": "Dira lenaneo la gago la mathomo ka tlase.",
  "home.listOptions": "Dikgetho tsa lenaneo",
  "home.sharedMembers": "Maloko a lenaneo leo le abelanwago",
  "home.colourOption": "Mmala {n}",
  "home.newListPlaceholder": "Lenaneo le lefsa",
  "home.add": "Oketša",
  "home.deleteListTitle": 'Phumola "{title}"?',
  "home.leaveListTitle": 'Tlogela "{title}"?',
  "home.deleteListBody":
    "Se se e tloša go bohle bao ba lego go lona. Ga se kgone go bušetšwa morago.",
  "home.untitledFallback": "lenaneo le",

  "list.backToLists": "Boela mananeong",
  "list.untitled": "Lenaneo le le se nago sehlogo",
  "list.errorAdd": "E paletšwe go oketša mošomo. Lekola kgokagano ya gago gomme o leke gape.",
  "list.emptyBody": "Ga go selo lenaneong le gabjale.",
  "list.markDone": 'Swaya "{title}" e dirilwe',
  "list.markNotDone": 'Swaya "{title}" e sešo ya dirwa',
  "list.star": "Naledi",
  "list.unstar": "Tloša naledi",
  "list.taskOptions": "Dikgetho tsa mošomo",
  "list.doneCount": "E DIRILWE ({n})",
  "list.addItemPlaceholder": "Oketša selo",
  "list.addItem": "Oketša selo",

  "signin.intro":
    "Tsena ka imeile ya gago — re tla go romela kgokagano, ga go phasewete yeo o swanetšego go e gopola.",
  "signin.sending": "E a romela…",
  "signin.emailMeALink": "Nthomele kgokagano",
  "signin.checkEmailTitle": "Lekola imeile ya gago",
  "signin.delivered": "E romelwe go {email}. E bule sedirišwaneng se go tšwela pele.",
  "signin.sent":
    "Re romile kgokagano ya go tsena go {email}. E ka tšea metsotso e mmalwa go fihla — e bule sedirišwaneng se go tšwela pele.",
  "signin.codeHint": "O bala imeile lefelong le lengwe? Ngwala khoutu ya yona mo.",
  "signin.codePlaceholder": "Khoutu ya dinomoro tše 6",
  "signin.signingIn": "E a tsena…",
  "signin.signInWithCode": "Tsena ka khoutu",
  "signin.resendIn": "Romela gape ka {s}s",
  "signin.resendLink": "Romela kgokagano gape",
  "signin.useDifferentEmail": "Šomiša imeile ye nngwe",
  "signin.errorCodeInvalid": "Khoutu yeo ga e a swana. Lekola imeile gomme o leke gape.",
  "signin.errorCodeExpired": "Khoutu yeo e feditše nako — romela gape gomme o leke gape.",

  "magicLink.signingIn": "E a go tsenya…",
  "magicLink.failed":
    "Kgokagano yeo ga e a šoma — mohlomongwe e feditše nako goba e šetše e šomišitšwe.",
  "magicLink.backToSignIn": "Boela go tsena",

  "invite.notAnInvite": "Seo ga se bonagale bjalo ka talšetšo.",
  "invite.wrongAccount":
    "Talšetšo ye e rometšwe atereseng ye nngwe ya imeile, e sego yeo o tsenego ka yona.",
  "invite.expired": "Talšetšo yeo e feletšwe ke nako. Kgopela e mpšha.",
  "invite.acceptFailed":
    "E paletšwe go amogela talšetšo. Lekola kgokagano ya gago gomme o leke gape.",
  "invite.backToLists": "Boela mananeong a gago",
  "invite.joining": "E a tsena…",

  "sharing.leaveTitle": "Tlogela lenaneo le?",
  "sharing.removeTitle": "Tloša {name}?",
  "sharing.removeBody": "Ba tla hloka talšetšo ye mpsha gore ba bone lenaneo le gape.",
  "sharing.membersTitle": "Go abelanwe le",
  "sharing.checking": "Go a lekolwa…",
  "sharing.noMembersYet": "Ga go yo a kilego a amogela talšetšo ya lenaneo le gabjale.",
  "sharing.owner": "mong",
  "sharing.you": "wena",
  "sharing.working": "Go a šomiwa…",
  "sharing.shareFailedTitle": "E paletšwe go abelana",
  "sharing.shareListTitle": "Abelana lenaneo le",
  "sharing.whoFor": '"{title}" ke ya mang?',
  "sharing.emailAddressPlaceholder": "Aterese ya imeile",
  "sharing.sendingInvite": 'E romela talšetšo ya "{title}"…',
  "sharing.invitedTo": '{email} o laleditšwe go "{title}".',
  "sharing.sendInvite": "Romela taletšo",
  "sharing.done": "E dirilwe",

  "deleteAccount.title": "Phumola akhaonto ya gago ya Dielys",
  "deleteAccount.sentMessage":
    "Ge {email} e na le akhaonto ya Dielys, re romile kgokagano go netefatša go e phumola. Kgokagano e šoma metsotso e 15.",
  "deleteAccount.explainBody":
    "Mananeo ao o nnoši go ona a sepela le ona. Mananeo ao e lego a gago ao go nago le ba bangwe go ona a fetela go yo a bilego go ona nako e telele — bona ba dula, wena ga o dule.",
  "deleteAccount.submitCta": "Nthomele kgokagano ya go phumola",
  "deleteAccount.confirmIntro":
    "Se se phumola akhaonto ya gago ka mo go sa felego. Ga se kgone go bušetšwa morago.",
  "deleteAccount.confirmCta": "Phumola akhaonto ya ka",
  "deleteAccount.deleting": "E phumola akhaonto…",
  "deleteAccount.done": "Akhaonto ya gago e phumotšwe.",
  "deleteAccount.missingToken": "Kgokagano ye ga e na token ya yona.",
  "deleteAccount.linkExpired":
    "Kgokagano ye e feletšwe ke nako goba e šetše e šomišitšwe. Kgopela e mpšha.",

  "settings.title": "Dipeakanyo",
  "settings.account": "Akhaonto",
  "settings.email": "Imeile",
  "settings.emailUnknown": "Ga e tsebje",
  "settings.newItemsGoTo": "Dilo tše difsa di ya",
  "settings.top": "Godimo",
  "settings.bottom": "Tlase",
  "settings.mobileSync": "Go sepetšana ga mogala ka morago",
  "settings.backgroundSyncDescription": "Boloka dilista di mpshafaditšwe ge app e tswaletšwe",
  "settings.syncLoadError": "E paletšwe go laiša peakanyo ya go sepetšana ka morago.",
  "settings.syncSaveError": "E paletšwe go boloka. Lekola kgokagano ya gago gomme o leke gape.",
  "settings.everyMinutes.one": "Motsotso o mongwe le o mongwe wa {n}",
  "settings.everyMinutes.other": "Metsotso ye {n} ye mengwe le ye mengwe",
  "settings.everyHours.one": "Iri e nngwe le e nngwe ya {n}",
  "settings.everyHours.other": "Diiri tše {n} tše dingwe le tše dingwe",
  "settings.language": "Polelo",
  "settings.languageSystemDefault": "Tlwaelo ya sistimi",
  "settings.privacyPolicy": "Pholisi ya Sephiri",
  "settings.signOut": "Tšwa",
  "settings.deleteAccountQuestion": "Phumola akhaonto ya gago?",
  "settings.deleteAccount": "Phumola akhaonto",
  "settings.deletingAccount": "E phumola akhaonto…",
  "settings.deleteAccountFailed":
    "E paletšwe go phumola akhaonto ya gago: {code}. Ga go selo seo se fetogilego.",
  "settings.deleteAccountNetworkError":
    "E paletšwe go phumola akhaonto ya gago. Lekola kgokagano ya gago gomme o leke gape.",

  "play.getApp": "Hwetša app go Google Play",

  "sharing.note":
    "Ke aterese yeo fela ya imeile yeo e tla kgona go tsenela. E šoma matšatši a šupago.",
  "help.title": "Thušo",
  "help.listsTitle": "Mananeo",
  "help.listsBody":
    "Ngwala leina ka lepokising le le ka fase go dira lenaneo, gomme o kgotle lenaneo go le bula. Goga mananeo go fetola tatelano ya ona — mogaleng, gatelela o swaře pele. Lelokelelo la {menu} kgauswi le lenaneo le lengwe le le lengwe le le fa leina le lefsa, le fetola mmala wa lona, le le abelana goba le le phumola. Mebala e gona sedirišweng sa gago fela; mang le mang yo o abelanago le yena o ikgethela ya gagwe.",
  "help.itemsTitle": "Dilo",
  "help.itemsBody":
    "Ngwala ka lepokising le le ka fase ga lenaneo go oketša selo. Swaya selo ge se dirilwe — se ya karolong ya E dirilwe, moo o ka se bušetšago. Tšea selo naledi gore se dule godimo. Ka Dipeakanyong o ka kgetha ge e ba dilo tše difsa di ya godimo goba fase.",
  "help.sharingTitle": "Go abelana lenaneo",
  "help.sharingBody":
    "Ke motho yo a dirilego lenaneo fela yo a ka le abelanago. Bula lelokelelo la {menu}, kgetha Abelana gomme o ngwale aterese ya imeile ya motho yo mongwe. Ba hwetša imeile ye e nago le kgokagano ya taletšo. Kgokagano e šoma matšatši a šupago gomme ke aterese yeo fela ya imeile yeo e ka e amogelago, ka fao ba swanetše go tsena ka aterese ye e swanago — wepeng goba ka app ya Android.",
  "help.togetherTitle": "Go diriša lenaneo leo le abelanwago",
  "help.togetherBody":
    "Mang le mang yo a lego lenaneong leo le abelanwago a ka oketša, swaya, fetoša, tšea naledi le go beakanya dilo lefsa, gomme diphetogo di bonala go bohle ka metsotswana. Leswao la batho kgauswi le lenaneo le bontšha gore le abelanwa le mang.",
  "help.leavingTitle": "Go tlogela le go phumola",
  "help.leavingBody":
    "Mang le mang a ka tlogela lenaneo leo le abelanwago ka lelokelelo la lona la {menu}, gomme mong a ka ntšha batho ka leswao la batho. Ke mong fela yo a ka phumolago lenaneo, gomme go le phumola go le tloša go bohle. Mang le mang yo a tlogelago goba a ntšhwago o hloka taletšo ye mpsha go boa.",
  "help.offlineTitle": "Ntle le kgokagano",
  "help.offlineBody":
    "Die Lys e šoma ntle le kgokagano. Diphetogo tša gago di bolokwa sedirišweng sa gago gomme di romelwa gang ge o boetše inthaneteng.",
  "help.accountTitle": "Akhaonto ya gago",
  "help.accountBody":
    "Ga go na phasewete. Tsena ka aterese ya gago ya imeile gomme re go romela kgokagano le khoutu ya dinomoro tše 6 — diriša efe goba efe ye bonolo. Tsena ka imeile ye e swanago mogaleng wa gago le wepeng go bona mananeo a swanago.",
  "sharing.howItWorks": "Kamoo go abelana go šomago ka gona",
  "list.notifications": "Ditsebišo",
  "notify.title": "Ntsebiše ge motho yo mongwe…",
  "notify.added": "A oketša selo",
  "notify.checked": "A swaya selo, goba a se bušetša",
  "notify.deleted": "A phumola selo",
  "notify.updated": "A fetola leina goba a nea selo naledi",
  "notify.phoneOnly": "Di fihla mogaleng wa gago, ka app ya Die Lys. Sebadi se ga se di bontšhe.",
  "notify.save": "Boloka",
};
