import type { Messages } from "./en.js";

/**
 * DRAFT (#42). Machine-assisted Xitsonga, not yet reviewed by a
 * first-language speaker — same caveat as `android/.../res/values-ts/
 * strings.xml`, which this file's shared vocabulary is ported from. Treat
 * every string here as a suggestion until it has been reviewed.
 */
export const ts: Messages = {
  "common.settings": "Vulunghiwa",
  "common.backToLists": "Tlhelela eka minxaxamelo",
  "common.cancel": "Khansela",
  "common.close": "Pfala",
  "common.delete": "Susa",
  "common.rename": "Cinca vito",
  "common.share": "Avelana",
  "common.edit": "Cinca",
  "common.leave": "Siya",
  "common.remove": "Susa",
  "common.on": "Pfulekile",
  "common.off": "Pfarikile",
  "common.closeMenu": "Pfala xikombiso",
  "common.loading": "Ya lodiwa…",
  "common.leaveBody": "U ta lava xirhambo lexintshwa leswaku u wu vona nakambe.",
  "common.errorRateLimited": "Ku ringeta ko tala ngopfu — ringeta nakambe endzhaku.",
  "common.errorGeneric": "Ku ni nchumu lowu nga hambangiki kahle. Ringeta nakambe.",

  "home.tagline": "Yi veke enxaxamelweni",
  "home.errorCreate":
    "A swi kotekanga ku tumbuluxa nxaxamelo. Languta vuhlanganisi bya wena kutani u ringeta nakambe.",
  "home.errorDeleteForbidden": "I n'wini ntsena la nga susaka nxaxamelo lowu.",
  "home.errorDelete": "A swi kotekanga ku susa nxaxamelo.",
  "home.errorLeave":
    "A swi kotekanga ku siya nxaxamelo. Languta vuhlanganisi bya wena kutani u ringeta nakambe.",
  "home.emptyTitle": "A ku na minxaxamelo sweswi",
  "home.emptyBody": "Endla nxaxamelo wa wena wo sungula ehansi.",
  "home.listOptions": "Swihlawulekisi swa nxaxamelo",
  "home.sharedMembers": "Swirho swa nxaxamelo lowu avelaniwaka",
  "home.colourOption": "Muhlovo {n}",
  "home.newListPlaceholder": "Nxaxamelo lowuntshwa",
  "home.add": "Engetela",
  "home.deleteListTitle": 'Susa "{title}"?',
  "home.leaveListTitle": 'Siya "{title}"?',
  "home.deleteListBody":
    "Leswi swi wu susa eka hinkwavo lava nga eka wona. Leswi a swi nge cinciwi.",
  "home.untitledFallback": "nxaxamelo lowu",

  "list.backToLists": "Tlhelela eka minxaxamelo",
  "list.untitled": "Nxaxamelo lowu nga riki na nhlokomhaka",
  "list.errorAdd":
    "A swi kotekanga ku engetela ntirho. Languta vuhlanganisi bya wena kutani u ringeta nakambe.",
  "list.emptyBody": "A ku na nchumu eka nxaxamelo lowu sweswi.",
  "list.markDone": 'Kombisa "{title}" tanihi leswi endliweke',
  "list.markNotDone": 'Kombisa "{title}" tanihi leswi nga si endliwiki',
  "list.star": "Nyeleti",
  "list.unstar": "Susa nyeleti",
  "list.taskOptions": "Swihlawulekisi swa ntirho",
  "list.doneCount": "SWI ENDLIWILE ({n})",
  "list.addItemPlaceholder": "Engetela nchumu",
  "list.addItem": "Engetela nchumu",

  "signin.intro":
    "Nghena hi imeyili ya wena — hi ta ku rhumela xihlanganisi, a ku na phasiwedi leyi u faneleke u yi tsundzuka.",
  "signin.sending": "Ya rhumela…",
  "signin.emailMeALink": "Ndzi rhumele xihlanganisi",
  "signin.checkEmailTitle": "Languta imeyili ya wena",
  "signin.delivered":
    "Yi rhumeriwe eka {email}. Yi pfule eka xitirhisiwa lexi leswaku u ya emahlweni.",
  "signin.sent":
    "Hi rhumele xihlanganisi xo nghena eka {email}. Swi nga teka timinete ti nga ri tingani leswaku yi fika — yi pfule eka xitirhisiwa lexi leswaku u ya emahlweni.",
  "signin.codeHint": "U hlaya imeyili kun'wana? Tsala khodi ya yona laha.",
  "signin.codePlaceholder": "Khodi ya tinomboro ta 6",
  "signin.signingIn": "Yi ya nghena…",
  "signin.signInWithCode": "Nghena hi khodi",
  "signin.resendIn": "Rhumela nakambe hi {s}s",
  "signin.resendLink": "Rhumela xihlanganisi nakambe",
  "signin.useDifferentEmail": "Tirhisa imeyili yin'wana",
  "signin.errorCodeInvalid":
    "Khodi yoleyo a yi pfanananga. Languta imeyili kutani u ringeta nakambe.",
  "signin.errorCodeExpired":
    "Khodi yoleyo yi hundzeriwe hi nkarhi — rhumela nakambe kutani u ringeta nakambe.",

  "magicLink.signingIn": "Yi ya ku nghenisa…",
  "magicLink.failed":
    "Xihlanganisi xexo a xi tirhanga — kumbexana xi hundzeriwe hi nkarhi kumbe xi tirhisiwile khale.",
  "magicLink.backToSignIn": "Tlhelela eku ngheneni",

  "invite.notAnInvite": "Sweswo a swi vonaki swi fana ni xirhambo.",
  "invite.wrongAccount":
    "Xirhambo lexi xi rhumeriwe eka adirese yin'wana ya imeyili, ku nga ri leyi u ngheneke ha yona.",
  "invite.expired": "Xirhambo xexo xi hundzeriwe hi nkarhi. Kombela lexintshwa.",
  "invite.acceptFailed":
    "A swi kotekanga ku amukela xirhambo. Languta vuhlanganisi bya wena kutani u ringeta nakambe.",
  "invite.backToLists": "Tlhelela eka minxaxamelo ya wena",
  "invite.joining": "Yi ya joyina…",

  "sharing.leaveTitle": "Siya nxaxamelo lowu?",
  "sharing.removeTitle": "Susa {name}?",
  "sharing.removeBody": "Va ta lava xirhambo lexintshwa leswaku va wu vona nakambe nxaxamelo lowu.",
  "sharing.membersTitle": "Ku avelaniwe na",
  "sharing.checking": "Swa kamberiwa…",
  "sharing.noMembersYet": "A ku na loyi a tshamaka a amukela xirhambo xa nxaxamelo lowu sweswi.",
  "sharing.owner": "n'wini",
  "sharing.you": "wena",
  "sharing.working": "Swa tirhiwa…",
  "sharing.shareFailedTitle": "A swi kotekanga ku avelana",
  "sharing.shareListTitle": "Avelana nxaxamelo lowu",
  "sharing.whoFor": '"{title}" i ya mani?',
  "sharing.emailAddressPlaceholder": "Adirese ya imeyili",
  "sharing.sendingInvite": 'Yi rhumela xirhambo xa "{title}"…',
  "sharing.invitedTo": '{email} u rhambiwile eka "{title}".',
  "sharing.sendInvite": "Rhumela xirhambo",
  "sharing.done": "Swi endliwile",

  "deleteAccount.title": "Susa akhawunti ya wena ya Dielys",
  "deleteAccount.sentMessage":
    "Loko {email} yi ri na akhawunti ya Dielys, hi rhumele xihlanganisi xo tiyisisa ku yi susa. Xihlanganisi xi tirha timinete ta 15.",
  "deleteAccount.explainBody":
    "Minxaxamelo leyi u nga eka yona u ri wexe yi famba na wena. Minxaxamelo ya wena leyi van'wana va nga eka yona yi ya eka loyi a nga eka yona nkarhi wo leha — vona va sala, wena a wu sali.",
  "deleteAccount.submitCta": "Ndzi rhumele xihlanganisi xo susa",
  "deleteAccount.confirmIntro":
    "Leswi swi susa akhawunti ya wena hi masiku hinkwawo. A swi nge cinciwi.",
  "deleteAccount.confirmCta": "Susa akhawunti ya mina",
  "deleteAccount.deleting": "Ku susa akhawunti…",
  "deleteAccount.done": "Akhawunti ya wena yi susiwile.",
  "deleteAccount.missingToken": "Xihlanganisi lexi a xi na token ya xona.",
  "deleteAccount.linkExpired":
    "Xihlanganisi lexi xi hundzeriwe hi nkarhi kumbe xi tirhisiwile khale. Kombela lexintshwa.",

  "settings.title": "Vulunghiwa",
  "settings.account": "Akhawunti",
  "settings.email": "Imeyili",
  "settings.emailUnknown": "A swi tiveki",
  "settings.newItemsGoTo": "Swilo leswintshwa swi ya",
  "settings.top": "Ehenhla",
  "settings.bottom": "Ehansi",
  "settings.mobileSync": "Ku hlanganisiwa ka riqingho endzhaku",
  "settings.syncLoadError": "A swi kotekanga ku layisha vulunghiso bya ku hlanganisiwa endzhaku.",
  "settings.syncSaveError":
    "A swi kotekanga ku hlayisa. Languta vuhlanganisi bya wena kutani u ringeta nakambe.",
  "settings.everyMinutes.one": "Minute yin'wana na yin'wana ya {n}",
  "settings.everyMinutes.other": "Timinete ta {n} hinkwato",
  "settings.everyHours.one": "Awara yin'wana na yin'wana ya {n}",
  "settings.everyHours.other": "Tiawara ta {n} hinkwato",
  "settings.language": "Ririmi",
  "settings.languageSystemDefault": "Swa sisiteme",
  "settings.privacyPolicy": "Nawu wa Xihundla",
  "settings.signOut": "Huma",
  "settings.deleteAccountQuestion": "Susa akhawunti ya wena?",
  "settings.deleteAccount": "Susa akhawunti",
  "settings.deletingAccount": "Ku susa akhawunti…",
  "settings.deleteAccountFailed":
    "Swi tsandzekile ku susa akhawunti ya wena: {code}. A ku na lexi cinciweke.",
  "settings.deleteAccountNetworkError":
    "Swi tsandzekile ku susa akhawunti ya wena. Languta vuhlanganisi bya wena kutani u ringeta nakambe.",
};
