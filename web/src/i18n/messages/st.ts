import type { Messages } from "./en.js";

/**
 * DRAFT (#42). Machine-assisted Sesotho, not yet reviewed by a
 * first-language speaker — same caveat as `android/.../res/values-st/
 * strings.xml`, which this file's shared vocabulary is ported from. Treat
 * every string here as a suggestion until it has been reviewed.
 */
export const st: Messages = {
  "common.settings": "Ditlhophiso",
  "common.backToLists": "Khutlela manaaneng",
  "common.cancel": "Hlakola",
  "common.close": "Koala",
  "common.delete": "Hlakola",
  "common.rename": "Fetola lebitso",
  "common.share": "Arolelana",
  "common.edit": "Fetola",
  "common.leave": "Tlohela",
  "common.remove": "Tlosa",
  "common.on": "E Buletswe",
  "common.off": "E Kwetsoe",
  "common.closeMenu": "Koala lethathamo",
  "common.loading": "E a lodiwa…",
  "common.leaveBody": "O tla hloka memo e ntjha ho e bona hape.",
  "common.errorRateLimited": "Diteko tse ngata haholo — leka hape hamorao.",
  "common.errorGeneric": "Ho na le se sa lokang. Leka hape.",

  "home.tagline": "E behe lenaneng",
  "home.errorCreate": "E hlolehile ho theha lenane. Sheba khokahano ya hao mme o leke hape.",
  "home.errorDeleteForbidden": "Ke mong feela ya ka hlakolang lenane lena.",
  "home.errorDelete": "E hlolehile ho hlakola lenane.",
  "home.errorLeave": "E hlolehile ho tlohela lenane. Sheba khokahano ya hao mme o leke hape.",
  "home.emptyTitle": "Ha ho manane hajwale",
  "home.emptyBody": "Etsa lenane la hao la pele ka tlase.",
  "home.listOptions": "Dikgetho tsa lenane",
  "home.sharedMembers": "Ditho tsa lenane le arolelanweng",
  "home.colourOption": "Mmala {n}",
  "home.newListPlaceholder": "Lenane le letjha",
  "home.add": "Eketsa",
  "home.deleteListTitle": 'Hlakola "{title}"?',
  "home.leaveListTitle": 'Tlohela "{title}"?',
  "home.deleteListBody": "Sena se e tlosa ho bohle ba ho yona. Ha se khone ho khutliswa.",
  "home.untitledFallback": "lenane lena",

  "list.backToLists": "Khutlela manaaneng",
  "list.untitled": "Lenane le se nang sehlooho",
  "list.errorAdd": "E hlolehile ho eketsa mosebetsi. Sheba khokahano ya hao mme o leke hape.",
  "list.emptyBody": "Ha ho letho lenaneng lena hajwale.",
  "list.markDone": 'Tshwaya "{title}" e entswe',
  "list.markNotDone": 'Tshwaya "{title}" e sa entswe',
  "list.star": "Naledi",
  "list.unstar": "Tlosa naledi",
  "list.taskOptions": "Dikgetho tsa mosebetsi",
  "list.doneCount": "E ENTSWE ({n})",
  "list.addItemPlaceholder": "Eketsa ntho",
  "list.addItem": "Eketsa ntho",

  "signin.intro":
    "Kena ka imeile ya hao — re tla o romella kgokahanyo, ha ho phasewete eo o lokelang ho e hopola.",
  "signin.sending": "E a romella…",
  "signin.emailMeALink": "Nthomelle kgokahanyo",
  "signin.checkEmailTitle": "Sheba imeile ya hao",
  "signin.delivered": "E fihlisitswe ho {email}. E bule sesebediswaneng sena ho tswela pele.",
  "signin.sent":
    "Re rometse kgokahanyo ya ho kena ho {email}. E ka nka metsotso e seng mekae ho fihla — e bule sesebediswaneng sena ho tswela pele.",
  "signin.codeHint": "O bala imeile sebakeng se seng? Ngola khoutu ya yona mona.",
  "signin.codePlaceholder": "Khoutu ya dinomoro tse 6",
  "signin.signingIn": "E a kena…",
  "signin.signInWithCode": "Kena ka khoutu",
  "signin.resendIn": "Romela hape ka {s}s",
  "signin.resendLink": "Romela kgokahanyo hape",
  "signin.useDifferentEmail": "Sebedisa imeile e nngwe",
  "signin.errorCodeInvalid": "Khoutu eo ha e a tshwana. Sheba imeile mme o leke hape.",
  "signin.errorCodeExpired": "Khoutu eo e felletswe ke nako — romela hape mme o leke hape.",

  "magicLink.signingIn": "E a o kenya…",
  "magicLink.failed":
    "Kgokahanyo eo ha e a sebetsa — mohlomong e felletswe ke nako kapa e se e sebedisitswe.",
  "magicLink.backToSignIn": "Khutlela ho kena",

  "invite.notAnInvite": "Seo ha se shebahale jwaloka memo.",
  "invite.wrongAccount":
    "Memo ena e romelletswe atereseng e nngwe ya imeile, eseng eo o kenneng ka yona.",
  "invite.expired": "Memo eo e felletswe ke nako. Kopa e ntjha.",
  "invite.acceptFailed": "E hlolehile ho amohela memo. Sheba khokahano ya hao mme o leke hape.",
  "invite.backToLists": "Khutlela manaaneng a hao",
  "invite.joining": "E a kena…",

  "sharing.leaveTitle": "Tlohela lenane lena?",
  "sharing.removeTitle": "Tlosa {name}?",
  "sharing.removeBody": "Ba tla hloka memo e ntjha ho bona lenane lena hape.",
  "sharing.membersTitle": "Ho arolelanwe le",
  "sharing.checking": "E a hlahlojwa…",
  "sharing.noMembersYet": "Ha ho motho ya kileng a amohela memo ya lenane lena hajwale.",
  "sharing.owner": "mong",
  "sharing.you": "wena",
  "sharing.working": "E a sebetsa…",
  "sharing.shareFailedTitle": "E hlolehile ho arolelana",
  "sharing.shareListTitle": "Arolelana lenane lena",
  "sharing.whoFor": '"{title}" ke ya mang?',
  "sharing.emailAddressPlaceholder": "Aterese ya imeile",
  "sharing.sendingInvite": 'E romela memo ya "{title}"…',
  "sharing.invitedTo": '{email} o memilwe ho "{title}".',
  "sharing.sendInvite": "Romela memo",
  "sharing.done": "E entswe",

  "deleteAccount.title": "Hlakola akhaonto ya hao ya Dielys",
  "deleteAccount.sentMessage":
    "Haeba {email} e na le akhaonto ya Dielys, re rometse kgokahanyo ho netefatsa ho e hlakola. Kgokahanyo e sebetsa metsotso e 15.",
  "deleteAccount.explainBody":
    "Manane ao o leng ho wona o le mong a tsamaya le wona. Manane ao e leng a hao ao ba bang ba lengo ho wona a fetela ho ya bileng ho wona nako e telele — ba a dula, wena ha o dule.",
  "deleteAccount.submitCta": "Nthomelle kgokahanyo ya ho hlakola",
  "deleteAccount.confirmIntro":
    "Sena se hlakola akhaonto ya hao ka ho sa feleng. Ha se khone ho khutliswa.",
  "deleteAccount.confirmCta": "Hlakola akhaonto ya ka",
  "deleteAccount.deleting": "E hlakola akhaonto…",
  "deleteAccount.done": "Akhaonto ya hao e hlakotswe.",
  "deleteAccount.missingToken": "Kgokahanyo ena e haelloa ke token ya yona.",
  "deleteAccount.linkExpired":
    "Kgokahanyo ena e felletswe ke nako kapa e se e sebedisitswe. Kopa e ntjha.",

  "settings.title": "Ditlhophiso",
  "settings.account": "Akhaonto",
  "settings.email": "Imeile",
  "settings.emailUnknown": "Ha e tsejwe",
  "settings.newItemsGoTo": "Dintho tse ntjha di ya",
  "settings.top": "Hodimo",
  "settings.bottom": "Tlase",
  "settings.mobileSync": "Ho kopanngwa ha fono ka nako e sa tsejweng",
  "settings.backgroundSyncDescription": "Boloka manane a ntjhafaditswe ha app e kwetswe",
  "settings.syncLoadError": "E hlolehile ho kenya tlhophiso ya ho kopanngwa ka nako e sa tsejweng.",
  "settings.syncSaveError": "E hlolehile ho boloka. Sheba khokahano ya hao mme o leke hape.",
  "settings.everyMinutes.one": "Motsotso o mong le o mong {n}",
  "settings.everyMinutes.other": "Metsotso e {n} e nngwe le e nngwe",
  "settings.everyHours.one": "Hora e nngwe le e nngwe {n}",
  "settings.everyHours.other": "Dihora tse {n} tse nngwe le tse nngwe",
  "settings.language": "Puo",
  "settings.languageSystemDefault": "Tlwaelo ya sistimi",
  "settings.privacyPolicy": "Pholisi ya Lekunutu",
  "settings.signOut": "Tswa",
  "settings.deleteAccountQuestion": "Hlakola akhaonto ya hao?",
  "settings.deleteAccount": "Hlakola akhaonto",
  "settings.deletingAccount": "E hlakola akhaonto…",
  "settings.deleteAccountFailed":
    "E hlolehile ho hlakola akhaonto ya hao: {code}. Ha ho se fetohileng.",
  "settings.deleteAccountNetworkError":
    "E hlolehile ho hlakola akhaonto ya hao. Sheba khokahano ya hao mme o leke hape.",

  "play.getApp": "Fumana app ho Google Play",

  "sharing.note":
    "Ke aterese eo feela ya imeile e tla kgona ho kena. E sebetsa matsatsi a supileng.",
  "help.title": "Thuso",
  "help.listsTitle": "Manane",
  "help.listsBody":
    "Ngola lebitso lebokoseng le ka tlase ho etsa lenane, ebe o tobetsa lenane ho le bula. Hula manane ho fetola tatellano ya ona — fonong, tobetsa o tshware pele. Lenane la {menu} pela lenane ka leng le le fa lebitso le letjha, le fetola mmala wa lona, le le arolelana kapa le le hlakola. Mebala e teng sesebedisweng sa hao feela; mang kapa mang eo o arolelanang le yena o ikgethela ya hae.",
  "help.itemsTitle": "Dintho",
  "help.itemsBody":
    "Ngola lebokoseng le ka tlase ho lenane ho eketsa ntho. Tshwaya ntho ha e phethilwe — e ya karolong ya E entswe, moo o ka e kgutlisang. Beha ntho naledi hore e dule hodimo. Ho Disetting o ka kgetha hore na dintho tse ntjha di ya hodimo kapa tlase.",
  "help.sharingTitle": "Ho arolelana lenane",
  "help.sharingBody":
    "Ke motho ya entseng lenane feela ya ka le arolelanang. Bula lenane la {menu}, kgetha Arolelana ebe o ngola aterese ya imeile ya motho e mong. Ba fumana imeile e nang le lehokela la memo. Lehokela le sebetsa matsatsi a supileng mme ke aterese eo feela ya imeile e ka le amohelang, kahoo ba tlameha ho kena ka aterese e tshwanang — webong kapa ho app ya Android.",
  "help.togetherTitle": "Ho sebedisa lenane le arolelanweng",
  "help.togetherBody":
    "Mang kapa mang ya lenaneng le arolelanweng a ka eketsa, tshwaya, fetola, beha naledi le ho hlophisa dintho botjha, mme diphetoho di hlaha ho bohle ka metsotswana. Letshwao la batho pela lenane le bontsha hore le arolelanwa le mang.",
  "help.leavingTitle": "Ho tlohela le ho hlakola",
  "help.leavingBody":
    "Mang kapa mang a ka tlohela lenane le arolelanweng ka lenane la lona la {menu}, mme mong a ka tlosa batho ka letshwao la batho. Ke mong feela ya ka hlakolang lenane, mme ho le hlakola ho le tlosa ho bohle. Mang kapa mang ya tlohelang kapa ya tloswang o hloka memo e ntjha ho kgutla.",
  "help.offlineTitle": "Ntle le kgokahano",
  "help.offlineBody":
    "Die Lys e sebetsa ntle le kgokahano. Diphetoho tsa hao di bolokwa sesebedisweng sa hao mme di romelwa hang ha o kgutlela inthaneteng.",
  "help.accountTitle": "Akhaonto ya hao",
  "help.accountBody":
    "Ha ho na phasewete. Kena ka aterese ya hao ya imeile mme re o romella lehokela le khoutu ya dinomoro tse 6 — sebedisa efe kapa efe e bonolo. Kena ka imeile e tshwanang fonong ya hao le webong ho bona manane a tshwanang.",
  "sharing.howItWorks": "Kamoo ho arolelana ho sebetsang kateng",
};
