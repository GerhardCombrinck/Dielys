import type { Messages } from "./en.js";

/**
 * DRAFT (#42). Machine-assisted Tshivenda, not yet reviewed by a
 * first-language speaker — same caveat as `android/.../res/values-ve/
 * strings.xml`, which this file's shared vocabulary is ported from. Treat
 * every string here as a suggestion until it has been reviewed.
 */
export const ve: Messages = {
  "common.settings": "Zwiimiswa",
  "common.backToLists": "Vhuyani kha mitevhe",
  "common.cancel": "Khanselani",
  "common.close": "Vala",
  "common.delete": "Thutha",
  "common.rename": "Shandukisa dzina",
  "common.share": "Kovhekana",
  "common.edit": "Shandukisa",
  "common.leave": "Litshani",
  "common.remove": "Bvisani",
  "common.on": "Yo Vulwa",
  "common.off": "Yo Valwa",
  "common.closeMenu": "Vala menyu",
  "common.loading": "I khou lodiwa…",
  "common.leaveBody": "Ni ḓo ṱoḓa u rambiwa huswa u itela u u vhona hafhu.",
  "common.errorRateLimited": "Milingo minzhi lwa u fhira — lingedzani hafhu nga murahu.",
  "common.errorGeneric": "Hu na tshine tsha si khou tea. Lingedzani hafhu.",

  "home.tagline": "I vhee kha mutevhe",
  "home.errorCreate":
    "Zwo kundelwa u sika mutevhe. Sedzani vhukwamani hanu nahone ni lingedze hafhu.",
  "home.errorDeleteForbidden": "Ndi mune fhedzi ane a nga thutha uyu mutevhe.",
  "home.errorDelete": "Zwo kundelwa u thutha mutevhe.",
  "home.errorLeave":
    "Zwo kundelwa u litsha mutevhe. Sedzani vhukwamani hanu nahone ni lingedze hafhu.",
  "home.emptyTitle": "A hu na mitevhe zwino",
  "home.emptyBody": "Itani mutevhe waṋu wa u thoma fhasi.",
  "home.listOptions": "Khetho dza mutevhe",
  "home.sharedMembers": "Miraḓo ya mutevhe wo kovhekanwaho",
  "home.colourOption": "Muvhala {n}",
  "home.newListPlaceholder": "Mutevhe muswa",
  "home.add": "Engedzani",
  "home.deleteListTitle": 'Thutha "{title}"?',
  "home.leaveListTitle": 'Litshani "{title}"?',
  "home.deleteListBody":
    "Hezwi zwi u bvisa kha vhoṱhe vhane vha vha khawo. Hezwi a zwi nge zwa dovha zwa vhuyelela.",
  "home.untitledFallback": "uyu mutevhe",

  "list.backToLists": "Vhuyani kha mitevhe",
  "list.untitled": "Mutevhe u si na thoho",
  "list.errorAdd":
    "Zwo kundelwa u engedza mushumo. Sedzani vhukwamani hanu nahone ni lingedze hafhu.",
  "list.emptyBody": "A hu na tshithu kha uyu mutevhe zwino.",
  "list.markDone": 'Sumbedzani "{title}" sa zwo itwaho',
  "list.markNotDone": 'Sumbedzani "{title}" sa zwi songo itwa',
  "list.star": "Naledzi",
  "list.unstar": "Bvisa naledzi",
  "list.taskOptions": "Khetho dza mushumo",
  "list.doneCount": "ZWO ITWA ({n})",
  "list.addItemPlaceholder": "Engedzani tshithu",
  "list.addItem": "Engedzani tshithu",

  "signin.intro":
    "Dzhenani nga imeili yanu — ri do ni rumela vhukwamani, a hu na phasiwete ine na fanela u i humbula.",
  "signin.sending": "I khou rumela…",
  "signin.emailMeALink": "Nthumeleni vhukwamani",
  "signin.checkEmailTitle": "Sedzani imeili yanu",
  "signin.delivered": "Yo rumelwa kha {email}. I vulani kha ino tshishumiswa uri ni bvele phanda.",
  "signin.sent":
    "Ro rumela vhukwamani ha u dzhena kha {email}. I nga dzhia mimunithi mituku uri i swike — i vulani kha ino tshishumiswa uri ni bvele phanda.",
  "signin.codeHint": "Ni khou vhala imeili huṅwe? Ṅwalani khoudu yayo fhano.",
  "signin.codePlaceholder": "Khoudu ya nomboro dza 6",
  "signin.signingIn": "I khou dzhena…",
  "signin.signInWithCode": "Dzhenani nga khoudu",
  "signin.resendIn": "Rumelani hafhu nga {s}s",
  "signin.resendLink": "Rumelani vhukwamani hafhu",
  "signin.useDifferentEmail": "Shumisani inwe imeili",
  "signin.errorCodeInvalid":
    "Yeneyo khoudu a yo ngo fana. Sedzani imeili nahone ni lingedze hafhu.",
  "signin.errorCodeExpired":
    "Yeneyo khoudu tshifhinga tshayo tsho fhela — rumelani hafhu nahone ni lingedze hafhu.",

  "magicLink.signingIn": "I khou ni dzhenisa…",
  "magicLink.failed":
    "Honoho vhukwamani a vhu ngo shuma — khamusi ho fhela tshifhinga kana ho no shumiswa.",
  "magicLink.backToSignIn": "Vhuyani u dzhena",

  "invite.notAnInvite": "Zwenezwo a zwi vhonali sa u rambiwa.",
  "invite.wrongAccount":
    "Hoyu u rambiwa wo rumelwa kha iṅwe adiresi ya imeili, hu si ine wa dzhena ngayo.",
  "invite.expired": "Uyo u rambiwa wo no fhela tshifhinga. Humbelani muswa.",
  "invite.acceptFailed":
    "Zwo kundelwa u ṱanganedza u rambiwa. Sedzani vhukwamani hanu nahone ni lingedze hafhu.",
  "invite.backToLists": "Vhuyani kha mitevhe yanu",
  "invite.joining": "I khou dzhena…",

  "sharing.leaveTitle": "Litshani uyu mutevhe?",
  "sharing.removeTitle": "Bvisani {name}?",
  "sharing.removeBody": "Vha ḓo ṱoḓa u rambiwa huswa u itela u dovha vha u vhona uyu mutevhe.",
  "sharing.membersTitle": "Ho kovhekanwa na",
  "sharing.checking": "Hu khou sedzwa…",
  "sharing.noMembersYet": "A hu na muthu o no ṱanganedza u rambiwa ha uyu mutevhe zwino.",
  "sharing.owner": "mune",
  "sharing.you": "inwi",
  "sharing.working": "Hu khou shunwa…",
  "sharing.shareFailedTitle": "Zwo kundelwa u kovhekana",
  "sharing.shareListTitle": "Kovhekanani uyu mutevhe",
  "sharing.whoFor": '"{title}" ndi ya nnyi?',
  "sharing.emailAddressPlaceholder": "Adresi ya imeili",
  "sharing.sendingInvite": 'I khou rumela u rambiwa ha "{title}"…',
  "sharing.invitedTo": '{email} o rambiwa kha "{title}".',
  "sharing.sendInvite": "Rumelani tshiitisi",
  "sharing.done": "Zwo itwa",

  "deleteAccount.title": "Thutha akhaunthu yaṋu ya Dielys",
  "deleteAccount.sentMessage":
    "Arali {email} i na akhaunthu ya Dielys, ro rumela vhukwamani u itela u khwaṱhisedza u i thutha. Vhukwamani vhu shuma mimunithi ya 15.",
  "deleteAccount.explainBody":
    "Mitevhe ine na vha khayo ni noṱhe i tshimbila na inwi. Mitevhe ine ya vha yaṋu ine vhaṅwe vha vha khayo i ya kha ane a vha khayo tshifhinga tshilapfu — vhone vha dzula, inwi a ni dzuli.",
  "deleteAccount.submitCta": "Nthumeleni vhukwamani ha u thutha",
  "deleteAccount.confirmIntro":
    "Hezwi zwi thutha akhaunthu yaṋu nga u sa dovha. A zwi nge zwa dovha zwa vhuyelela.",
  "deleteAccount.confirmCta": "Thutha akhaunthu yanga",
  "deleteAccount.deleting": "I khou thutha akhaunthu…",
  "deleteAccount.done": "Akhaunthu yaṋu yo thuthwa.",
  "deleteAccount.missingToken": "Honoho vhukwamani a huna token yao.",
  "deleteAccount.linkExpired":
    "Honoho vhukwamani ho no fhela tshifhinga kana ho no shumiswa. Humbelani huswa.",

  "settings.title": "Zwiimiswa",
  "settings.account": "Akhaunthu",
  "settings.email": "Imeili",
  "settings.emailUnknown": "A zwi divhei",
  "settings.newItemsGoTo": "Zwithu zwiswa zwi ya",
  "settings.top": "Ntha",
  "settings.bottom": "Fhasi",
  "settings.mobileSync": "U tanganyiswa ha luṱingo nga murahu",
  "settings.backgroundSyncDescription": "Vhulungani mitevhe yo khwinifhadzwa musi app yo valwa",
  "settings.syncLoadError": "Zwo kundelwa u thoma zwiimiswa zwa u tanganyiswa nga murahu.",
  "settings.syncSaveError":
    "Zwo kundelwa u vhulunga. Sedzani vhukwamani hanu nahone ni lingedze hafhu.",
  "settings.everyMinutes.one": "Muṅwe na muṅwe minithi ya {n}",
  "settings.everyMinutes.other": "Mimunithi ya {n} yoṱhe",
  "settings.everyHours.one": "Awara iṅwe na iṅwe ya {n}",
  "settings.everyHours.other": "Awara dza {n} dzoṱhe",
  "settings.language": "Luambo",
  "settings.languageSystemDefault": "Zwa sisiteme",
  "settings.privacyPolicy": "Mulayo wa Tshiphiri",
  "settings.signOut": "Bvani",
  "settings.deleteAccountQuestion": "Thutha akhaunthu yaṋu?",
  "settings.deleteAccount": "Thutha akhaunthu",
  "settings.deletingAccount": "I khou thutha akhaunthu…",
  "settings.deleteAccountFailed":
    "Zwo kundelwa u thutha akhaunthu yaṋu: {code}. A hu na zwo shandukaho.",
  "settings.deleteAccountNetworkError":
    "Zwo kundelwa u thutha akhaunthu yaṋu. Sedzani vhukwamani hanu nahone ni lingedze hafhu.",

  "play.getApp": "Wanani app kha Google Play",

  "sharing.note":
    "Ndi yeneyo adresi ya imeili fhedzi ine ya do kona u dzhena. I shuma maduvha a sumbe.",
  "help.title": "Thuso",
  "help.listsTitle": "Mitevhe",
  "help.listsBody":
    "Ṅwalani dzina kha bogisi ḽi re fhasi u ita mutevhe, nahone ni kwame mutevhe u u vula. Kokodzani mitevhe u shandukisa u tevhekana hayo — kha founi, tsindelani ni fare u thoma. Mutevhe wa {menu} tsini ha mutevhe muṅwe na muṅwe u u ṋea dzina ḽiswa, u shandukisa muvhala wawo, u u kovhekana kana u u phumula. Mivhala i kha tshishumiswa tshaṋu fhedzi; muṅwe na muṅwe ane na kovhekana nae u ḓinangela yawe.",
  "help.itemsTitle": "Zwithu",
  "help.itemsBody":
    "Ṅwalani kha bogisi ḽi re fhasi ha mutevhe u engedza tshithu. Swayani tshithu musi tsho fhela — tshi ya kha tshipiḓa tsha Zwo itwa, hune na nga tshi vhuedzedza hone. Ṋeani tshithu naledzi uri tshi dzule nṱha. Kha Mivhekanyo ni nga nanga arali zwithu zwiswa zwi tshi ya nṱha kana fhasi.",
  "help.sharingTitle": "U kovhekana mutevhe",
  "help.sharingBody":
    "Ndi muthu we a ita mutevhe fhedzi ane a nga u kovhekana. Vulani mutevhe wa {menu}, nangani Kovhekana nahone ni ṅwale adresi ya imeili ya muṅwe muthu. Vha wana imeili i re na vhukwamani ha u ramba. Vhukwamani vhu shuma maḓuvha a sumbe nahone ndi yeneyo adresi ya imeili fhedzi ine ya nga vhu ṱanganedza, ngauralo vha fanela u dzhena nga adresi i fanaho — kha webu kana kha app ya Android.",
  "help.togetherTitle": "U shumisa mutevhe wo kovhekanwaho",
  "help.togetherBody":
    "Muṅwe na muṅwe o re kha mutevhe wo kovhekanwaho a nga engedza, swaya, shandukisa, ṋea naledzi na u dzudzanya zwithu hafhu, nahone tshanduko dzi vhonala kha vhoṱhe nga miniṱhinyana. Tshiga tsha vhathu tsini ha mutevhe tshi sumbedza uri wo kovhekanwa na nnyi.",
  "help.leavingTitle": "U litsha na u phumula",
  "help.leavingBody":
    "Muṅwe na muṅwe a nga litsha mutevhe wo kovhekanwaho nga mutevhe wawo wa {menu}, nahone muṋe a nga bvisa vhathu nga tshiga tsha vhathu. Ndi muṋe fhedzi ane a nga phumula mutevhe, nahone u u phumula zwi u bvisa kha vhoṱhe. Muṅwe na muṅwe ane a litsha kana a bviswa u ṱoḓa u rambwa hafhu u itela u vhuya.",
  "help.offlineTitle": "Hu si na vhukwamani",
  "help.offlineBody":
    "Die Lys i shuma hu si na vhukwamani. Tshanduko dzaṋu dzi vhulungwa kha tshishumiswa tshaṋu nahone dzi rumelwa musi ni tshi vhuyelela kha inthanethe.",
  "help.accountTitle": "Akhaunthu yaṋu",
  "help.accountBody":
    "A hu na phasiwede. Dzhenani nga adresi yaṋu ya imeili nahone ri ni rumela vhukwamani na khoudu ya nomboro dza 6 — shumisani iṅwe na iṅwe i leluwaho. Dzhenani nga imeili i fanaho kha founi yaṋu na kha webu u vhona mitevhe i fanaho.",
  "sharing.howItWorks": "Nḓila ine u kovhekana ha shuma ngayo",
  "list.notifications": "Ndivhadzo",
  "notify.title": "Ndivhadzeni musi muṅwe muthu…",
  "notify.added": "A tshi engedza tshithu",
  "notify.checked": "A tshi swaya tshithu, kana a tshi vhuedzedza",
  "notify.deleted": "A tshi thutha tshithu",
  "notify.updated": "A tshi shandukisa dzina kana a tshi ṋea tshithu naledzi",
  "notify.phoneOnly":
    "Dzi swika kha founu yaṋu, kha app ya Die Lys. Buraweza iyi a i dzi sumbedzi.",
  "notify.save": "Vhulunga",
};
