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
};
