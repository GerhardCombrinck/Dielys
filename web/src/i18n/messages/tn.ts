import type { Messages } from "./en.js";

/**
 * DRAFT (#42). Machine-assisted Setswana, not yet reviewed by a
 * first-language speaker — same caveat as `android/.../res/values-tn/
 * strings.xml`, which this file's shared vocabulary is ported from. Treat
 * every string here as a suggestion until it has been reviewed.
 */
export const tn: Messages = {
  "common.settings": "Dipeakanyo",
  "common.backToLists": "Boela kwa manaaneng",
  "common.cancel": "Khansela",
  "common.close": "Tswala",
  "common.delete": "Phimola",
  "common.rename": "Fetola leina",
  "common.share": "Abelana",
  "common.edit": "Fetola",
  "common.leave": "Tlogela",
  "common.remove": "Tlosa",
  "common.on": "E Buletse",
  "common.off": "E Tswetswe",
  "common.closeMenu": "Tswala lenane",
  "common.loading": "E a lodiwa…",
  "common.leaveBody": "O tla tlhoka taletso e ntšha go le bona gape.",
  "common.errorRateLimited": "Maiteko a mantsi thata — leka gape moragonyana.",
  "common.errorGeneric": "Go na le sengwe se se phoso. Leka gape.",

  "home.tagline": "E baye lenaaneng",
  "home.errorCreate": "Go paletswe go tlhama lenaane. Leba kgokagano ya gago mme o leke gape.",
  "home.errorDeleteForbidden": "Ke mong fela yo o ka phimolang lenaane le.",
  "home.errorDelete": "Go paletswe go phimola lenaane.",
  "home.errorLeave": "Go paletswe go tlogela lenaane. Leba kgokagano ya gago mme o leke gape.",
  "home.emptyTitle": "Ga go na manaane gonne jaana",
  "home.emptyBody": "Dira lenaane la gago la ntlha fa tlase.",
  "home.listOptions": "Dikgetho tsa lenaane",
  "home.sharedMembers": "Maloko a lenaane le le abelanwang",
  "home.colourOption": "Mmala {n}",
  "home.newListPlaceholder": "Lenaane le lešwa",
  "home.add": "Oketsa",
  "home.deleteListTitle": 'Phimola "{title}"?',
  "home.leaveListTitle": 'Tlogela "{title}"?',
  "home.deleteListBody":
    "Se se tlosa lenaane mo go botlhe ba ba mo go lone. Ga se kake sa boelwa morago.",
  "home.untitledFallback": "lenaane le",

  "list.backToLists": "Boela kwa manaaneng",
  "list.untitled": "Lenaane le le se nang setlhogo",
  "list.errorAdd": "Go paletswe go oketsa tiro. Leba kgokagano ya gago mme o leke gape.",
  "list.emptyBody": "Ga go na sepe mo lenaaneng le gone jaana.",
  "list.markDone": 'Tshwaya "{title}" e dirilwe',
  "list.markNotDone": 'Tshwaya "{title}" e ise e dirwe',
  "list.star": "Naledi",
  "list.unstar": "Tlosa naledi",
  "list.taskOptions": "Dikgetho tsa tiro",
  "list.doneCount": "GO DIRILWE ({n})",
  "list.addItemPlaceholder": "Oketsa selo",
  "list.addItem": "Oketsa selo",

  "signin.intro":
    "Tsena ka imeile ya gago — re tla go romela kgolagano, ga go na phasewete e o tshwanetseng go e gakologelwa.",
  "signin.sending": "E a romela…",
  "signin.emailMeALink": "Nthomele kgolagano",
  "signin.checkEmailTitle": "Leba imeile ya gago",
  "signin.delivered": "E rometswe go {email}. E bule mo sedirisiweng se go tswelela.",
  "signin.sent":
    "Re romile kgolagano ya go tsena go {email}. E ka tsaya metsotso e se mekae go goroga — e bule mo sedirisiweng se go tswelela.",
  "signin.codeHint": "O bala imeile kwa lefelong le lengwe? Kwala khoutu ya yone fano.",
  "signin.codePlaceholder": "Khoutu ya dinomoro tse 6",
  "signin.signingIn": "E a tsena…",
  "signin.signInWithCode": "Tsena ka khoutu",
  "signin.resendIn": "Romela gape ka {s}s",
  "signin.resendLink": "Romela kgolagano gape",
  "signin.useDifferentEmail": "Dirisa imeile e nngwe",
  "signin.errorCodeInvalid": "Khoutu eo ga e a tshwana. Leba imeile mme o leke gape.",
  "signin.errorCodeExpired": "Khoutu eo e feditse nako — romela gape mme o leke gape.",

  "magicLink.signingIn": "E a go tsenya…",
  "magicLink.failed":
    "Kgolagano eo ga e a dira — gongwe e feditse nako kgotsa e setse e dirisitswe.",
  "magicLink.backToSignIn": "Boela kwa go tseneng",

  "invite.notAnInvite": "Seo ga se lebege jaaka taletso.",
  "invite.wrongAccount":
    "Taletso e e rometswe kwa aterasing e nngwe ya imeile, e seng e o tsenyeng ka yone.",
  "invite.expired": "Taletso eo e fedile nako. Kopa e ntšha.",
  "invite.acceptFailed": "Go paletswe go amogela taletso. Leba kgokagano ya gago mme o leke gape.",
  "invite.backToLists": "Boela kwa manaaneng a gago",
  "invite.joining": "E a tsena…",

  "sharing.leaveTitle": "Tlogela lenaane le?",
  "sharing.removeTitle": "Tlosa {name}?",
  "sharing.removeBody": "Ba tla tlhoka taletso e ntšha go bona lenaane le gape.",
  "sharing.membersTitle": "Go abelanwe le",
  "sharing.checking": "Go a tlhatlhobiwa…",
  "sharing.noMembersYet": "Ga go na ope yo o kileng a amogela taletso ya lenaane le gone jaana.",
  "sharing.owner": "mong",
  "sharing.you": "wena",
  "sharing.working": "Go a diriwa…",
  "sharing.shareFailedTitle": "Go paletswe go abelana",
  "sharing.shareListTitle": "Abelana lenaane le",
  "sharing.whoFor": '"{title}" ke ya ga mang?',
  "sharing.emailAddressPlaceholder": "Aterese ya imeile",
  "sharing.sendingInvite": 'E romela taletso ya "{title}"…',
  "sharing.invitedTo": '{email} o laleditswe kwa "{title}".',
  "sharing.sendInvite": "Romela taletso",
  "sharing.done": "Go dirilwe",

  "deleteAccount.title": "Phimola akhaonto ya gago ya Dielys",
  "deleteAccount.sentMessage":
    "Fa {email} e na le akhaonto ya Dielys, re romile kgolagano go netefatsa go e phimola. Kgolagano e dira metsotso e le 15.",
  "deleteAccount.explainBody":
    "Manaane a o leng mo go one o le esi a tsamaya nao. Manaane a gago a a nang le ba bangwe a fetela kwa go yo o nnileng mo go one lobaka lo loleele — bone ba nna, wena ga o nne.",
  "deleteAccount.submitCta": "Nthomele kgolagano ya go phimola",
  "deleteAccount.confirmIntro":
    "Se se phimola akhaonto ya gago ka metlha. Ga se kake sa boelwa morago.",
  "deleteAccount.confirmCta": "Phimola akhaonto ya me",
  "deleteAccount.deleting": "E phimola akhaonto…",
  "deleteAccount.done": "Akhaonto ya gago e phimotswe.",
  "deleteAccount.missingToken": "Kgolagano e e tlhoka token ya yone.",
  "deleteAccount.linkExpired":
    "Kgolagano e e feditse nako kgotsa e setse e dirisitswe. Kopa e ntšha.",

  "settings.title": "Dipeakanyo",
  "settings.account": "Akhaonto",
  "settings.email": "Imeile",
  "settings.emailUnknown": "Ga e itsiwe",
  "settings.newItemsGoTo": "Dilo tse dišwa di ya",
  "settings.top": "Godimo",
  "settings.bottom": "Tlase",
  "settings.mobileSync": "Go tsamaisana ka nako ya semausu ya mogala",
  "settings.backgroundSyncDescription": "Boloka dilenaane di ntšhafaditswe fa app e tswetswe",
  "settings.syncLoadError": "Go paletswe go laisha tlhophiso ya go tsamaisana ka nako ya semausu.",
  "settings.syncSaveError": "Go paletswe go boloka. Leba kgokagano ya gago mme o leke gape.",
  "settings.everyMinutes.one": "Nako le nako ya motsotso {n}",
  "settings.everyMinutes.other": "Nako le nako ya metsotso e {n}",
  "settings.everyHours.one": "Nako le nako ya ura {n}",
  "settings.everyHours.other": "Nako le nako ya diura tse {n}",
  "settings.language": "Puo",
  "settings.languageSystemDefault": "Tlwaelo ya thulaganyo",
  "settings.privacyPolicy": "Pholisi ya Sephiri",
  "settings.signOut": "Tswa",
  "settings.deleteAccountQuestion": "Phimola akhaonto ya gago?",
  "settings.deleteAccount": "Phimola akhaonto",
  "settings.deletingAccount": "E phimola akhaonto…",
  "settings.deleteAccountFailed":
    "Go paletswe go phimola akhaonto ya gago: {code}. Ga go sepe se se fetogileng.",
  "settings.deleteAccountNetworkError":
    "Go paletswe go phimola akhaonto ya gago. Leba kgokagano ya gago mme o leke gape.",

  "play.getApp": "Bona app mo Google Play",

  "sharing.note":
    "Ke aterese eo fela ya imeile e e tla kgonang go tsenela. E dira malatsi a le supa.",
  "help.title": "Thuso",
  "help.listsTitle": "Manaane",
  "help.listsBody":
    "Kwala leina mo lebokoseng le le kwa tlase go dira lenaane, mme o tobetse lenaane go le bula. Goga manaane go fetola tatelano ya one — mo mogaleng, tobetsa o tshware pele. Lenaane la {menu} fa thoko ga lenaane lengwe le lengwe le le naya leina le lešwa, le fetola mmala wa lone, le le abelana kgotsa le le phimola. Mebala e mo sedirisiweng sa gago fela; mongwe le mongwe yo o abelanang le ene o itlhophela ya gagwe.",
  "help.itemsTitle": "Dilo",
  "help.itemsBody":
    "Kwala mo lebokoseng le le kwa tlase ga lenaane go oketsa selo. Tshwaya selo fa se weditswe — se ya kwa karolong ya Go dirilwe, kwa o ka se busetsang teng. Naya selo naledi gore se nne kwa godimo. Mo Dithulaganyong o ka tlhopha gore a dilo tse dišwa di ya kwa godimo kgotsa kwa tlase.",
  "help.sharingTitle": "Go abelana lenaane",
  "help.sharingBody":
    "Ke motho yo o dirileng lenaane fela yo o ka le abelanang. Bula lenaane la {menu}, tlhopha Abelana mme o kwale aterese ya imeile ya motho yo mongwe. Ba amogela imeile e e nang le kgolagano ya taletso. Kgolagano e dira malatsi a le supa mme ke aterese eo fela ya imeile e e ka e amogelang, ka jalo ba tshwanetse go tsena ka aterese e e tshwanang — mo webong kgotsa mo app ya Android.",
  "help.togetherTitle": "Go dirisa lenaane le le abelanwang",
  "help.togetherBody":
    "Mongwe le mongwe yo o mo lenaaneng le le abelanwang a ka oketsa, tshwaya, fetola, naya naledi le go rulaganya dilo sešwa, mme diphetogo di bonala mo go botlhe mo metsotswaneng. Letshwao la batho fa thoko ga lenaane le bontsha gore le abelanwa le mang.",
  "help.leavingTitle": "Go tlogela le go phimola",
  "help.leavingBody":
    "Mongwe le mongwe a ka tlogela lenaane le le abelanwang ka lenaane la lone la {menu}, mme mong a ka ntsha batho ka letshwao la batho. Ke mong fela yo o ka phimolang lenaane, mme go le phimola go le tlosa mo go botlhe. Mongwe le mongwe yo o tlogelang kgotsa yo o ntshiwang o tlhoka taletso e ntšha go boa.",
  "help.offlineTitle": "Kwa ntle ga kgolagano",
  "help.offlineBody":
    "Die Lys e dira kwa ntle ga kgolagano. Diphetogo tsa gago di bolokwa mo sedirisiweng sa gago mme di romelwa ka bonako fa o boetse mo inthaneteng.",
  "help.accountTitle": "Akhaonto ya gago",
  "help.accountBody":
    "Ga go na lefoko la sephiri. Tsena ka aterese ya gago ya imeile mme re go romelela kgolagano le khoutu ya dinomore tse 6 — dirisa epe fela e e motlhofo. Tsena ka imeile e e tshwanang mo mogaleng wa gago le mo webong go bona manaane a a tshwanang.",
  "sharing.howItWorks": "Kafa go abelana go dirang ka teng",
  "list.notifications": "Dikitsiso",
  "notify.title": "Nkitsise fa motho yo mongwe…",
  "notify.added": "A oketsa selo",
  "notify.checked": "A tshwaya selo, kgotsa a se busetsa",
  "notify.deleted": "A phimola selo",
  "notify.updated": "A fetola leina kgotsa a naya selo naledi",
  "notify.phoneOnly":
    "Di goroga mo mogaleng wa gago, mo app ya Die Lys. Sebatli se ga se di bontshe.",
  "notify.save": "Boloka",
};
