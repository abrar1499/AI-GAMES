#!/usr/bin/env python3
"""
The sentences the user reads aloud to train an offline voice in their own voice.

Mostly what the munshi actually says (khata replies with every kind of amount, names,
items and dates, and its chat lines from brain.js), plus everyday sentences so the
model hears every sound of Urdu. Amounts are written as words, the way they are said.
Written to sentences.txt, one per line, in a fixed order (the same every run).
"""
import os, random, re

HERE = os.path.dirname(os.path.abspath(__file__))
random.seed(7)

UNDER_100 = ("sifar ek do teen chaar paanch chhe saat aath nau das gyarah barah terah chaudah pandrah solah satrah atharah unnees "
    "bees ikkees baees teyees chaubees pachees chhabbees sattaees athaees untees tees iktees battees tentees chauntees paintees chhattees "
    "saintees artees untaalees chaalees iktaalees bayaalees taintaalees chavaalees paintaalees chhiyaalees saintaalees artaalees unchaas "
    "pachaas ikyaavan baavan tirpan chavvan pachpan chhappan sattaavan athaavan unsath saath iksath baasath tirsath chaunsath painsath "
    "chhiyaasath sarsath arsath unhattar sattar ikhattar bahattar tihattar chauhattar pachattar chhihattar sathattar athattar unaasi assi "
    "ikyaasi bayaasi tiraasi chauraasi pachaasi chhiyaasi sattaasi athaasi nawaasi nawway ikaanway baanway tiraanway chauraanway "
    "pachaanway chhiyaanway sattaanway athaanway ninyaanway").split()


def words(n):
    """The same as MyVoice.numberWords in the app."""
    if n < 100:
        return UNDER_100[n]
    out = []
    for size, w in ((10 ** 7, "crore"), (10 ** 5, "lakh"), (1000, "hazaar"), (100, "sau")):
        if n >= size:
            out.append(words(n // size) + " " + w)
            n %= size
    if n:
        out.append(UNDER_100[n])
    return " ".join(out)


# every number 1-99 at least a few times, and round and odd amounts of every size
def amounts():
    pool = list(range(1, 100)) * 2
    pool += [x * 10 for x in range(10, 100)] + [x * 50 for x in range(2, 60)]
    pool += [x * 100 for x in range(10, 100)] + [x * 1000 for x in range(1, 100)]
    pool += [random.randint(101, 99999) for _ in range(150)] + [x * 100000 for x in (1, 2, 3, 5, 10, 25)] + [10000000, 20000000]
    random.shuffle(pool)
    while True:
        for a in pool:
            yield words(a)


NAMES = [n.replace("_", " ") for n in "Ahmed Ali Bilal Ashraf Abrar Usman Hamza Imran Kamran Faisal Tariq Shahid Rashid Naveed Zubair Asif Sajid Waqas Adnan Fahad Ayesha Fatima Sana Hina Sara Nadia Rabia Saima Zainab Maryam Amna Bushra Kiran Mehwish Uzma Shazia Haji_sahab Chacha Mamu Khala Bhabhi Ustad_ji".split()]
ITEMS = ("petrol diesel bijli_ka_bill gas_ka_bill pani_ka_bill chai khana doodh sabzi rashan aata cheeni ghee chawal daal anday gosht "
         "murghi phal dawai doctor kiraya mobile load internet kapre joote school_fees kitabain rickshaw bus taxi gaari bike marammat "
         "naai dhobi tailor shaadi tohfa sadqa chanda cement eent sariya rait bajri lakri rang mazdoori").split()
ITEMS = [i.replace("_", " ") for i in ITEMS]
DAYS = ["Aaj", "Kal", "Parson", "Somwar ko", "Mangal ko", "Budh ko", "Jumeraat ko", "Jumme ko", "Hafte ko", "Itwar ko"]
MONTHS = "January February March April May June July August September October November December".split()


def khata():
    a = amounts()
    T = [
        lambda: f"{random.choice(ITEMS).capitalize()} ke {next(a)} rupay likh liye.",
        lambda: f"{random.choice(NAMES)} ko {next(a)} rupay diye, likh liya.",
        lambda: f"{random.choice(NAMES)} se {next(a)} rupay liye, likh liya.",
        lambda: f"{random.choice(NAMES)} ne {next(a)} rupay wapas kiye, likh liya.",
        lambda: f"{random.choice(NAMES)} ko {next(a)} rupay wapas kiye, likh liya.",
        lambda: f"{random.choice(NAMES)} ko {next(a)} rupay bheje, likh liya.",
        lambda: f"{random.choice(NAMES)} se {next(a)} rupay aaye, likh liya.",
        lambda: f"{next(a).capitalize()} rupay aamdani likh li.",
        lambda: f"{random.choice(NAMES)} ne aap ko {next(a)} rupay dene hain.",
        lambda: f"Aap ne {random.choice(NAMES)} ko {next(a)} rupay dene hain.",
        lambda: f"{random.choice(NAMES)} ke sath hisaab barabar hai.",
        lambda: f"{random.choice(DAYS)} ke kharche {next(a)} rupay.",
        lambda: f"Is mahine aaye {next(a)}, gaye {next(a)}, bachat {next(a)} rupay.",
        lambda: f"Is hafte ka kharcha {next(a)} rupay hai.",
        lambda: f"Logon se lene hain {next(a)}, aap ne dene hain {next(a)} rupay.",
        lambda: f"{random.choice(ITEMS).capitalize()} {next(a)} rupay wali entry mita doon? Haan ya nahi?",
        lambda: f"{words(random.randint(2, 30))} entries mita doon? Haan ya nahi?",
        lambda: f"{random.choice(DAYS)}: {random.choice(ITEMS)} ke {next(a)} rupay likh liye.",
        lambda: f"{words(random.randint(1, 28))} {random.choice(MONTHS)}: {random.choice(ITEMS)} ke {next(a)} rupay likh liye.",
        lambda: f"Is mahine andaazan munafa {next(a)} rupay.",
        lambda: f"Is mahine andaazan nuqsan {next(a)} rupay.",
        lambda: f"Stock mein: {words(random.randint(2, 500))} {random.choice(['bori cement', 'eent', 'kilo cheeni', 'thaili doodh', 'carton'])}.",
        lambda: f"{words(random.randint(2, 99))} {random.choice(['bori cement', 'kilo aata', 'darjan anday'])} tayyar, stock mein likh di.",
        lambda: f"{random.choice(ITEMS).capitalize()} ki bikri {next(a)} rupay, {random.choice(NAMES)} ko udhar par, likh li.",
        lambda: f"{random.choice(ITEMS).capitalize()} ki khareed {next(a)} rupay, {random.choice(NAMES)} se, likh li.",
        lambda: f"Bijli ka bill {random.choice(['udhar par likh diya', 'ki adaigi ho gayi'])}.",
        lambda: f"Aakhri: {random.choice(ITEMS)} {next(a)}.",
        lambda: f"{random.choice(ITEMS).capitalize()} par sab se zyada kharcha hua, {next(a)} rupay.",
        lambda: f"{random.choice(NAMES)} ke {next(a)} rupay baqi hain.",
        lambda: f"Kitne rupay the? {random.choice(ITEMS).capitalize()} ke?",
        lambda: f"Kis ko diye? {random.choice(NAMES)} ko ya {random.choice(NAMES)} ko?",
    ]
    out = []
    while len(out) < 950:
        s = random.choice(T)()
        s = s[0].upper() + s[1:]
        if s not in out:
            out.append(s)
    return out


EVERYDAY = """Assalam o alaikum! Bataiye, aaj kya likhna hai?
Wa alaikum assalam. Main theek hoon, aap sunaiye.
Theek hai, likh liya.
Haan ji, bilkul.
Nahi, abhi kuch nahi likha.
Maaf kijiye, samajh nahi aaya. Dobara bolein.
Zara aahista boliye.
Aur kuch?
Shukriya! Allah hafiz.
Khuda hafiz, phir baat hogi.
Koi baat nahi, ho jata hai.
Ji, main sun rahi hoon.
Aap ka hisaab bilkul theek hai.
Kya main yeh entry mita doon?
Yeh entry mita di.
Pichli entry wapas aa gayi.
Kaun si entry badalni hai?
Kitne rupay the?
Kis ko diye?
Kis se liye?
Kab ki baat hai, aaj ki ya kal ki?
Backup phone ke Downloads folder mein mehfooz hai.
Internet nahi chal raha, thori der baad koshish karein.
Mic se awaaz nahi aa rahi.
Barish ho rahi hai, chhatri le kar jaiye.
Aaj garmi bohat zyada hai, pani zyada piyen.
Sardi aa gayi hai, garam kapre nikaal lein.
Subah jaldi uthna sehat ke liye acha hai.
Bachon ki school ki fees jama karwani hai.
Ammi ne kaha tha ke sabzi le aana.
Abbu ki dawai khatam ho gayi hai.
Dukaan par aaj rash bohat tha.
Gaari ka tyre panchar ho gaya.
Bhai ki shaadi agle mahine hai.
Eid par naye kapre lene hain.
Ramzan mein sehri aur iftari ka kharcha barh jata hai.
Bijli phir chali gayi.
Gas ka pressure bohat kam hai.
Mehngai bohat barh gayi hai.
Is dafa bachat kam hui.
Agle mahine se kharcha kam karna hai.
Qarz jaldi utaar dena chahiye.
Udhar dene se pehle soch lena chahiye.
Hisaab saaf ho to dosti pakki rehti hai.
Thora thora bachaya jaye to bohat ho jata hai.
Paisay ped par nahi ugte.
Jitni chadar ho, utne paon phelao.
Sabr ka phal meetha hota hai.
Mehnat karne walon ki haar nahi hoti.
Waqt ki qadar karni chahiye.
Ghar se nikalte waqt darwaza band kar dena.
Chabi table par rakhi hai.
Mera phone charge par laga do.
Khana tayyar hai, aa jaiye.
Chai bana doon?
Doodh wala aaj nahi aaya.
Kal bazaar jaana hai.
Sabzi mandi mein tamatar sasta tha.
Pyaz ka bhaao phir barh gaya.
Aata bees kilo ka thaila lena hai.
Cheeni do kilo aur chawal paanch kilo.
Mehmaan aa rahe hain, gosht le aao.
Mobile mein load khatam ho gaya.
Internet ka package karwana hai.
Bijli ka bill bohat zyada aaya hai.
Pani ki motor kharab ho gayi.
Mistri ko bulana parega.
Rang wala kal aayega.
Cement ki do bori aur chahiye.
Mazdoor ki dihari de di.
Ghar ka kiraya pehli tareekh ko dena hai.
Tankhwah kab aayegi?
Is mahine bonus mila hai.
Dukaan ka maal khatam hone wala hai.
Supplier ko order de diya.
Grahak ne baqi paise kal dene ka kaha hai.
Rozana ki bikri acha rahi.
Aaj munafa kam hua.
Nuqsan se bachne ke liye hisaab rakhna zaroori hai.
Sab kuch likh lo, bhool jate hain.
Yaad dila dena, warna bhool jaunga.
Main aap ki munshi hoon, sab yaad rakhti hoon.
Aap ka din acha guzre.
Subah bakhair!
Shab bakhair, aaram se soiye.
Mubarak ho!
Bohat khushi hui sun kar.
Afsos hua sun kar.
Allah behtar karega.
Inshallah sab theek ho jayega.
Mashallah, bohat acha!
Jazak Allah.
Ji haan, zaroor.
Ji nahi, aisa nahi hai.
Shayad, pakka nahi keh sakti.
Ek minute ruk jaiye.
Bas, itna hi.
Chaliye, phir milte hain.""".split("\n")


def chat():
    s = open(os.path.join(HERE, "..", "brain.js"), encoding="utf-8").read()
    m = re.findall(r"\[\s*'((?:[^'\\]|\\.)+)'\s*,\s*'((?:[^'\\]|\\.)+)'\s*,\s*'((?:[^'\\]|\\.)+)'\s*\]", s)
    out = []
    for ro, ur, _ in m:
        ro = ro.replace("\\'", "'")
        if re.search(r"[A-Za-z]\d", ro):        # a name with a number in it (K2) would be read as a word
            continue
        if re.match(r"^[A-Za-z]", ro) and re.search(r"[؀-ۿ]", ur) and len(ro.split()) >= 3:
            ro = re.sub(r"\d[\d,]*", lambda x: words(int(x.group().replace(",", ""))), ro)
            if ro not in out:
                out.append(ro)
    return out


def main():
    lines = []
    for s in EVERYDAY + chat() + khata():
        s = s.strip()
        if s and s not in lines:
            lines.append(s)
    # mix them, so a tired voice at the end is not all one kind of sentence
    head, rest = lines[:20], lines[20:]
    random.shuffle(rest)
    lines = head + rest
    open(os.path.join(HERE, "sentences.txt"), "w", encoding="utf-8").write("\n".join(lines) + "\n")
    words_total = sum(len(l.split()) for l in lines)
    print(len(lines), "sentences,", words_total, "words, about", round(words_total / 2.5 / 60), "minutes of reading")


if __name__ == "__main__":
    main()
