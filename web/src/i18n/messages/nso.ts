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
  "settings.syncLoadError": "E paletšwe go laiša peakanyo ya go sepetšana ka morago.",
  "settings.syncSaveError": "E paletšwe go boloka. Lekola kgokagano ya gago gomme o leke gape.",
  "settings.everyMinutes.one": "Motsotso o mongwe le o mongwe wa {n}",
  "settings.everyMinutes.other": "Metsotso ye {n} ye mengwe le ye mengwe",
  "settings.everyHours.one": "Iri e nngwe le e nngwe ya {n}",
  "settings.everyHours.other": "Diiri tše {n} tše dingwe le tše dingwe",
  "settings.language": "Polelo",
  "settings.languageSystemDefault": "Tlwaelo ya sistimi",
  "settings.signOut": "Tšwa",
  "settings.deleteAccountQuestion": "Phumola akhaonto ya gago?",
  "settings.deleteAccount": "Phumola akhaonto",
  "settings.deletingAccount": "E phumola akhaonto…",
  "settings.deleteAccountFailed":
    "E paletšwe go phumola akhaonto ya gago: {code}. Ga go selo seo se fetogilego.",
  "settings.deleteAccountNetworkError":
    "E paletšwe go phumola akhaonto ya gago. Lekola kgokagano ya gago gomme o leke gape.",
};
