"""Hand-authored source for sentences 21..200 of fixtures/parse/sentences.json (CC0).

SENTENCES: (space-separated tokens, English translation). Token surfaces concatenate to the text.
A token may carry a selector `surface#lemma-or-pos` when the lexicon has several readings for it.
LEXICON: `surface lemma reading pos` (4 fields) or `lemma reading pos` (3 fields, surface == lemma).
Readings are hiragana; lemma is the dictionary form (NFKC-normalised for full/half-width input).
"""

SENTENCES: list[tuple[str, str]] = [
    # --- verb / auxiliary chains
    ("母 は 毎朝 パン を 食べ て いる 。", "My mother eats bread every morning."),
    ("昨日 は 何 も 食べ なかっ た 。", "I didn't eat anything yesterday."),
    ("私 は 水 を 飲み たく ない 。", "I don't want to drink water."),
    ("彼女 は 歌 を 歌い たかっ た 。", "She wanted to sing a song."),
    ("先生 は 学生 に 本 を 読ま せ た 。", "The teacher made the students read a book."),
    ("この 魚 は 生 で 食べ られる 。", "You can eat this fish raw."),
    ("子供 は 母 に 野菜 を 食べ させ られ た 。", "The child was made by his mother to eat vegetables."),
    ("雨 に 降ら れ て 困っ た 。", "I was rained on and had trouble."),
    ("友達 が 部屋 に 来 て 、 一緒 に 勉強 し まし た 。", "A friend came to my room and we studied together."),
    ("早く 寝 ない と 、 明日 起き られ ない 。", "If I don't sleep early I can't get up tomorrow."),
    ("手紙 を 書い て から 、 出かけ ます 。", "I will go out after writing the letter."),
    ("まだ 宿題 を し て い ませ ん 。", "I haven't done my homework yet."),
    ("窓 を 開け て ください 。", "Please open the window."),
    ("ここ で 写真 を 撮っ て は いけ ませ ん 。", "You must not take photos here."),
    ("彼 は 走っ て 学校 へ 行っ た 。", "He ran to school."),
    ("ご飯 を 食べ た 後 で 、 散歩 し ましょ う 。", "Let's take a walk after we eat."),
    ("何 を 読ん で#て いる の です か 。", "What are you reading?"),
    ("先週 買っ た 本 は まだ 読ん で#て い ない 。", "I haven't read the book I bought last week yet."),
    ("電気 を つけ た まま 寝 て しまっ た 。", "I fell asleep with the light on."),
    ("傘 を 持っ て 行か なけれ ば なら ない 。", "I have to take an umbrella."),
    # --- adjectives
    ("この 部屋 は 広く て 明るい 。", "This room is spacious and bright."),
    ("昨日 は 寒かっ た です 。", "It was cold yesterday."),
    ("そんな に 高く ない 店 を 探し て いる 。", "I'm looking for a shop that isn't so expensive."),
    ("今日 は 天気 が よく なかっ た 。", "The weather wasn't good today."),
    ("この カレー は 辛 すぎる 。", "This curry is too spicy."),
    ("彼女 は とても 優しく て 親切 だ 。", "She is very gentle and kind."),
    ("部屋 を きれい に 掃除 し た 。", "I cleaned the room until it was tidy."),
    ("美味しかっ た けど 、 少し 高かっ た 。", "It was tasty, but a little expensive."),
    ("暑く なっ て き た ね 。", "It's gotten hot, hasn't it."),
    ("眠い から 、 もう 寝 たい 。", "I'm sleepy, so I want to go to bed now."),
    ("面白く ない 映画 だっ た 。", "It was an uninteresting movie."),
    ("この 道 は 狭く て 危ない 。", "This road is narrow and dangerous."),
    # --- compounds kept by mode C (JMdict has the compound)
    ("新幹線 で 東京 へ 行く 。", "I go to Tokyo by bullet train."),
    ("運転手 に 道 を 聞い た 。", "I asked the driver for directions."),
    ("夏休み に 図書館 で 勉強 する 。", "I study at the library during summer vacation."),
    ("自動車 工場 で 働い て いる 。", "I work at a car factory."),
    ("国際電話 を かけ た 。", "I made an international call."),
    ("携帯電話 を 忘れ て しまっ た 。", "I forgot my mobile phone."),
    ("世界中 の 人 が 見 て いる 。", "People all over the world are watching."),
    ("大学 の 入学試験 は 来月 です 。", "The university entrance exam is next month."),
    ("日本料理 は 健康 に いい 。", "Japanese food is good for your health."),
    ("駅 の 近く に 郵便局 が ある 。", "There is a post office near the station."),
    ("新聞記者 に なり たい 。", "I want to become a newspaper reporter."),
    ("食料品 売り場 は 地下 に ある 。", "The grocery section is in the basement."),
    # --- compounds that fall back to mode B (no JMdict entry for the whole)
    ("公園 清掃 に 参加 し た 。", "I took part in the park clean-up."),
    ("日本語 学習 は 楽しい 。", "Learning Japanese is fun."),
    ("夏休み 宿題 は もう 終わっ た 。", "The summer vacation homework is already done."),
    ("会議室 予約 を お願い し ます 。", "Please book the meeting room."),
    ("週末 旅行 の 計画 を 立てる 。", "I make plans for a weekend trip."),
    ("朝食 時間 は 七 時 です 。", "Breakfast time is seven o'clock."),
    ("台風 接近 中 は 外 に 出 ない 。", "I won't go out while the typhoon is approaching."),
    ("地下鉄 乗り場 は どこ です か 。", "Where is the subway platform?"),
    ("図書館 返却 期限 は 明日 です 。", "The library return deadline is tomorrow."),
    ("英語 教科書 を 忘れ た 。", "I forgot my English textbook."),
    ("夜間 外出 は 禁止 さ れ て いる 。", "Going out at night is prohibited."),
    ("映画 鑑賞 が 趣味 です 。", "My hobby is watching movies."),
    # --- kana-only writing of words that have kanji lemmas
    ("これ は わたし の かばん です 。", "This is my bag."),
    ("きのう ともだち と ごはん を たべ た 。", "Yesterday I ate with a friend."),
    ("ありがとう ござい ます 。", "Thank you very much."),
    ("おはよう 、 げんき です か 。", "Good morning, how are you?"),
    ("ねこ が まど の そば に いる 。", "The cat is beside the window."),
    ("すこし やすん で#て から いく 。", "I'll rest a little and then go."),
    ("こんにちは 、 はじめまして 。", "Hello, nice to meet you."),
    ("いま 、 なに を し て いる の ？", "What are you doing now?"),
    ("たぶん あした は あめ だろ う 。", "It will probably rain tomorrow."),
    ("とても おいしい ので 、 また たべ たい 。", "It's so delicious that I want to eat it again."),
    # --- iteration marks
    ("人々 は 静か に 待っ て いる 。", "The people are waiting quietly."),
    ("時々 、 母 に 電話 する 。", "I sometimes phone my mother."),
    ("色々 な 店 を 見 て 回っ た 。", "I looked around at various shops."),
    ("日々 の 生活 は 忙しい 。", "Daily life is busy."),
    ("様々 な 国 から 学生 が 来 て いる 。", "Students are coming from various countries."),
    ("こゝろ の 中 で 彼 に 謝っ た 。", "In my heart I apologised to him."),
    ("あゝ 、 疲れ た 。", "Ah, I'm tired."),
    ("山々 が 雪 で 白く なっ て いる 。", "The mountains have turned white with snow."),
    ("少々 お 待ち ください 。", "Please wait a moment."),
    # --- numbers and counters, full/half-width digits
    ("りんご を 三つ 買っ た 。", "I bought three apples."),
    ("3 人 で 映画 を 見 た 。", "Three of us watched a movie."),
    ("５ 時 に 駅 で 会い ましょ う 。", "Let's meet at the station at five."),
    ("この 本 は 1200 円 です 。", "This book is 1,200 yen."),
    ("今日 は ３ 月 １５ 日 だ 。", "Today is March 15th."),
    ("猫 が 二 匹 いる 。", "There are two cats."),
    ("２０２６ 年 に 日本 へ 行く 。", "I will go to Japan in 2026."),
    ("5 階 に 会議室 が ある 。", "The meeting room is on the fifth floor."),
    ("私 は 十 歳 です 。", "I am ten years old."),
    ("彼 は 100 メートル を 十 秒 で 走る 。", "He runs 100 metres in ten seconds."),
    ("一 週間 に 三 回 、 泳い で#て いる 。", "I swim three times a week."),
    ("本 を 5 冊 借り た 。", "I borrowed five books."),
    ("午後 3 時 半 に 会い ます 。", "We'll meet at half past three in the afternoon."),
    ("２ 本 の 傘 を 持っ て いる 。", "I have two umbrellas."),
    ("三十 分 待っ た 。", "I waited thirty minutes."),
    ("１０ 人 くらい 来 た 。", "About ten people came."),
    ("一人 で 旅行 に 行く の が 好き だ 。", "I like travelling alone."),
    # --- katakana loanwords and width variants
    ("コーヒー を 二 杯 飲ん だ#た 。", "I drank two cups of coffee."),
    ("新しい パソコン を 買い たい 。", "I want to buy a new computer."),
    ("レストラン で ピザ を 食べ た 。", "I ate pizza at a restaurant."),
    ("スマホ の バッテリー が ない#adjective 。", "My phone battery is dead."),
    ("インターネット で ニュース を 見る 。", "I watch the news on the internet."),
    ("テレビ の ボリューム を 上げ て ください 。", "Please turn up the TV volume."),
    ("バス が 来#こ ない ので タクシー に 乗っ た 。", "The bus didn't come, so I took a taxi."),
    ("ホテル の ロビー で 待ち合わせ し た 。", "We arranged to meet in the hotel lobby."),
    ("アイスクリーム が 食べ たい 。", "I want to eat ice cream."),
    ("ｶﾒﾗ を 持っ て 旅行 に 行く 。", "I go on a trip with a camera."),
    ("ＡＢＣ 会社 の メール を 読ん だ#た 。", "I read an email from ABC Company."),
    ("ミルクティー に 砂糖 を 入れる 。", "I put sugar in my milk tea."),
    # --- names
    ("田中 さん は 先生 です 。", "Mr. Tanaka is a teacher."),
    ("山田 さん と 京都 へ 行っ た 。", "I went to Kyoto with Ms. Yamada."),
    ("鈴木 先生 は 大阪 の 出身 です 。", "Professor Suzuki is from Osaka."),
    ("私 の 名前 は 佐藤 花子 です 。", "My name is Hanako Sato."),
    ("北海道 は 冬 に 雪 が 多い 。", "It snows a lot in Hokkaido in winter."),
    ("高橋 君 は 野球 が 上手 だ 。", "Takahashi is good at baseball."),
    ("太郎 は 富士山 に 登っ た 。", "Taro climbed Mt. Fuji."),
    ("ニューヨーク に は 友達 が いる 。", "I have a friend in New York."),
    ("伊藤 さん に 手紙 を 送っ た 。", "I sent a letter to Mr. Ito."),
    ("中村 さん は 福岡 に 住ん で#て いる 。", "Ms. Nakamura lives in Fukuoka."),
    ("マリア さん は スペイン から 来 た 。", "Maria came from Spain."),
    ("小林 先生 の 授業 は 分かり やすい 。", "Professor Kobayashi's classes are easy to follow."),
    # --- sentence-final particles and colloquial contractions
    ("今日 は 寒い ね 。", "It's cold today, isn't it."),
    ("それ は 本当 だ よ 。", "That's true, you know."),
    ("明日 は 来#こ ない の ？", "Aren't you coming tomorrow?"),
    ("もう 帰る わ 。", "I'm going home now."),
    ("行こ う ぜ 。", "Let's go."),
    ("いい 天気 だ なあ 。", "What nice weather."),
    ("食べ ちゃっ た 。", "I ate it all up."),
    ("全部 飲ん じゃっ た よ 。", "I drank it all, you know."),
    ("何 し てる の ？", "What are you doing?"),
    ("今 テレビ 見 てる 。", "I'm watching TV now."),
    ("まだ 寝 てる かも しれ ない 。", "He may still be sleeping."),
    ("そんな こと 言わ ない で よ 。", "Don't say such things."),
    ("早く 行か なきゃ 。", "I have to go quickly."),
    ("そう#adverb じゃ ない よ 。", "That's not so."),
    ("ちょっと 待っ て ね 。", "Wait a moment, okay."),
    ("そんな の 知ら ない よ 。", "I don't know anything like that."),
    ("やっぱり 行く こと に し た 。", "After all, I decided to go."),
    ("いい じゃ ない か 。", "Isn't that fine?"),
    ("すごい ね 、 これ 。", "This is amazing."),
    ("ここ に 置い とく よ 。", "I'll leave it here."),
    # --- subtitle-style fragments
    ("ええ 、 分かり まし た 。", "Yes, I understand."),
    ("待っ て ！", "Wait!"),
    ("どこ へ 行く の ？", "Where are you going?"),
    ("それ で ？", "And then?"),
    ("ありがとう 。", "Thanks."),
    ("すみ ませ ん 、 遅れ まし た 。", "Sorry, I'm late."),
    ("本当 です か ？", "Really?"),
    ("早く 逃げ て ！", "Run away quickly!"),
    ("なに 、 それ ？", "What is that?"),
    ("もう 一度 言っ て ください 。", "Please say that once more."),
    ("静か に し て 。", "Be quiet."),
    ("どうして 泣い て いる の ？", "Why are you crying?"),
    ("ごめん 、 ごめん 。", "Sorry, sorry."),
    ("いただき ます 。", "Let's eat."),
    ("お疲れ様 でし た 。", "Thank you for your hard work."),
    ("大丈夫 ？", "Are you okay?"),
    # --- assorted grammar
    ("雨 が 降っ て いる から 、 傘 を 持っ て 行き なさい 。", "It's raining, so take an umbrella."),
    ("彼 が 来る か どう か 分から ない 。", "I don't know whether he will come."),
    ("本 を 読む の は 楽しい 。", "Reading books is fun."),
    ("もし 時間 が あれ ば 、 手伝っ て ください 。", "If you have time, please help."),
    ("医者 に なる ため に 勉強 し て いる 。", "I study in order to become a doctor."),
    ("お金 が なく て 困っ て いる 。", "I have no money and am in trouble."),
    ("この 道 を まっすぐ 行く と 駅 に 着く 。", "If you go straight along this road you reach the station."),
    ("友達 に 会う ため 、 東京 へ 行っ た 。", "I went to Tokyo to meet a friend."),
    ("彼 は 日本語 が 上手 に 話せる 。", "He can speak Japanese well."),
    ("先生 に 褒め られ て 嬉しかっ た 。", "I was happy to be praised by the teacher."),
    ("これ は 私 が 作っ た 料理 です 。", "This is a dish I made."),
    ("食べ すぎ て お腹 が 痛い 。", "I ate too much and my stomach hurts."),
    ("あの 人 は 誰 です か 。", "Who is that person?"),
    ("彼女 は 歌 が 上手 な 歌手 だ 。", "She is a singer who is good at singing."),
    ("休み の 日 は 家 で 本 を 読む 。", "On days off I read at home."),
    ("ここ で 少し 休ん で#て も いい です か 。", "May I rest here a little?"),
    ("兄 は 私 より 背 が 高い 。", "My older brother is taller than I am."),
    ("もっと 勉強 し なけれ ば なら ない 。", "I have to study more."),
    ("彼 は 笑い ながら 話し た 。", "He talked while laughing."),
    ("夜 に なる と 、 星 が 見え ます 。", "When night falls, stars can be seen."),
    ("今朝 は 電車 が 込ん で#て い まし た 。", "The train was crowded this morning."),
    ("約束 を 守る の は 大切 です 。", "Keeping promises is important."),
    ("彼 は 医者 だ そう です 。", "I hear he is a doctor."),
    ("この 店 の ラーメン は 安く て 美味しい 。", "The ramen at this shop is cheap and tasty."),
    ("私 は 犬 より 猫 の ほう が 好き です 。", "I like cats better than dogs."),
    ("風邪 を ひい て 、 学校 を 休ん だ#た 。", "I caught a cold and stayed home from school."),
    ("彼 が 来 たら 、 教え て ください 。", "Please tell me when he comes."),
    ("窓 を 閉め て も いい です か 。", "May I close the window?"),
]


LEXICON = """
# particles
は は particle
が が particle
を を particle
に に particle
へ へ particle
で で particle
で て で particle
と と particle
も も particle
の の particle
か か particle
ね ね particle
よ よ particle
わ わ particle
ぜ ぜ particle
なあ なあ particle
から から particle
より より particle
ので ので particle
けど けど particle
くらい くらい particle
かも かも particle
じゃ じゃ particle
ば ば particle
て て particle
ながら ながら particle
# auxiliaries
ます ます auxiliary
まし ます まし auxiliary
ましょ ます ましょ auxiliary
ませ ます ませ auxiliary
ん ぬ ん auxiliary
う う auxiliary
だろ だ だろ auxiliary
です です auxiliary
でし です でし auxiliary
だ だ auxiliary
だ た だ auxiliary
だっ だ だっ auxiliary
た た auxiliary
たい たい auxiliary
たく たい たく auxiliary
たかっ たい たかっ auxiliary
ない ない auxiliary
ない ない ない adjective
なく ない なく adjective
なかっ ない なかっ auxiliary
なけれ ない なけれ auxiliary
なきゃ なきゃ なきゃ auxiliary
られる られる auxiliary
られ られる られ auxiliary
れ れる れ auxiliary
させ させる させ auxiliary
せ せる せ auxiliary
ちゃっ ちゃう ちゃっ auxiliary
じゃっ じゃう じゃっ auxiliary
てる てる てる auxiliary
とく とく とく auxiliary
たら たら auxiliary
そう そう そう auxiliary
# pronouns, demonstratives, adverbs, adnominals
私 わたし pronoun
わたし 私 わたし pronoun
彼 かれ pronoun
彼女 かのじょ pronoun
これ これ pronoun
それ それ pronoun
ここ ここ pronoun
どこ どこ pronoun
何 なに pronoun
なに 何 なに pronoun
誰 だれ pronoun
この この adnominal
あの あの adnominal
そんな そんな adnominal
そう そう そう adverb
どう 如何 どう adverb
どうして 如何して どうして adverb
とても とても adverb
もう もう adverb
また 又 また adverb
まだ 未だ まだ adverb
もっと もっと adverb
たぶん 多分 たぶん adverb
ちょっと 一寸 ちょっと adverb
やっぱり 矢張り やっぱり adverb
すこし 少し すこし adverb
少し すこし adverb
もし 若し もし adverb
まっすぐ 真っ直ぐ まっすぐ adverb
早く 早い はやく adjective
# interjections
ありがとう ありがとう interjection
こんにちは こんにちは interjection
はじめまして はじめまして interjection
おはよう おはよう interjection
ええ ええ interjection
ごめん 御免 ごめん interjection
お疲れ様 おつかれさま interjection
あゝ ああ あゝ interjection
# prefix and suffixes
お お prefix
さん さん suffix
君 くん suffix
やすい やすい suffix
人 にん suffix
日 にち suffix
月 がつ suffix
年 ねん suffix
時 じ suffix
分 ぷん suffix
半 はん suffix
本 ほん suffix
匹 ひき suffix
冊 さつ suffix
杯 はい suffix
歳 さい suffix
秒 びょう suffix
円 えん suffix
階 かい suffix
回 かい suffix
週間 しゅうかん suffix
中 ちゅう suffix
たち たち suffix
# numbers (digit forms get lemma NFKC-normalised; kanji numerals are JMdict words)
一 いち numeral
二 に numeral
三 さん numeral
七 しち numeral
十 じゅう numeral
三十 さんじゅう numeral
三つ みっつ numeral
100 ひゃく number
1200 せんにひゃく number
3 さん number
5 ご number
１０ 10 じゅう number
１５ 15 じゅうご number
２ 2 に number
２０２６ 2026 にせんにじゅうろく number
３ 3 さん number
５ 5 ご number
ＡＢＣ ABC えーびーしー symbol
# names (no seq)
東京 とうきょう name
京都 きょうと name
大阪 おおさか name
北海道 ほっかいどう name
富士山 ふじさん name
福岡 ふくおか name
ニューヨーク にゅーよーく name
スペイン すぺいん name
マリア まりあ name
田中 たなか name
山田 やまだ name
鈴木 すずき name
佐藤 さとう name
花子 はなこ name
高橋 たかはし name
太郎 たろう name
伊藤 いとう name
中村 なかむら name
小林 こばやし name
# verbs (inflected surface, dictionary form, surface reading)
いる いる verb
い いる い verb
ある ある verb
あれ ある あれ verb
いく 行く いく verb
行く いく verb
行き 行く いき verb
行っ 行く いっ verb
行か 行く いか verb
行こ 行く いこ verb
いけ 行ける いけ verb
いただき 頂く いただき verb
ござい 御座る ござい verb
しまっ 仕舞う しまっ verb
しれ 知れる しれ verb
ください 下さる ください verb
なさい なさる なさい verb
し する し verb
さ する さ verb
する する verb
なる なる verb
なり なる なり verb
なっ なる なっ verb
なら なる なら verb
すぎ すぎる すぎ verb
すぎる すぎる verb
来 来る き verb
来 来る こ verb
来る 来る くる verb
すみ 済む すみ verb
な だ な auxiliary
日本 にほん noun
本当 ほんとう noun
公園 こうえん noun
清掃 せいそう noun
参加 さんか noun
趣味 しゅみ noun
鑑賞 かんしょう noun
き 来る き verb
出 出る で verb
出かけ 出かける でかけ verb
見 見る み verb
見え 見える みえ verb
見る 見る みる verb
食べ 食べる たべ verb
たべ 食べる たべ verb
飲み 飲む のみ verb
飲ん 飲む のん verb
歌い 歌う うたい verb
読ま 読む よま verb
読む 読む よむ verb
読ん 読む よん verb
降ら 降る ふら verb
降っ 降る ふっ verb
困っ 困る こまっ verb
寝 寝る ね verb
起き 起きる おき verb
書い 書く かい verb
開け 開ける あけ verb
撮っ 撮る とっ verb
走っ 走る はしっ verb
走る 走る はしる verb
待っ 待つ まっ verb
待ち 待つ まち verb
忘れ 忘れる わすれ verb
持っ 持つ もっ verb
聞い 聞く きい verb
言っ 言う いっ verb
言わ 言う いわ verb
話し 話す はなし verb
話せる 話せる はなせる verb
知ら 知る しら verb
着く 着く つく verb
置い 置く おい verb
入れる 入れる いれる verb
つけ 点ける つけ verb
かけ 掛ける かけ verb
ひい 引く ひい verb
会い 会う あい verb
会う 会う あう verb
買い 買う かい verb
買っ 買う かっ verb
乗っ 乗る のっ verb
上げ 上げる あげ verb
借り 借りる かり verb
住ん 住む すん verb
働い 働く はたらい verb
作っ 作る つくっ verb
休ん 休む やすん verb
やすん 休む やすん verb
立てる 立てる たてる verb
終わっ 終わる おわっ verb
探し 探す さがし verb
手伝っ 手伝う てつだっ verb
掃除 掃除 そうじ noun
撮っ 撮る とっ verb
教え 教える おしえ verb
守る 守る まもる verb
笑い 笑う わらい verb
泣い 泣く ない verb
泳い 泳ぐ およい verb
疲れ 疲れる つかれ verb
褒め 褒める ほめ verb
込ん 込む こん verb
逃げ 逃げる にげ verb
閉め 閉める しめ verb
謝っ 謝る あやまっ verb
遅れ 遅れる おくれ verb
送っ 送る おくっ verb
登っ 登る のぼっ verb
回っ 回る まわっ verb
帰る 帰る かえる verb
分から 分かる わから verb
分かり 分かる わかり verb
# adjectives and adjectival nouns
いい いい adjective
よく 良い よく adjective
新しい あたらしい adjective
広く 広い ひろく adjective
明るい あかるい adjective
寒い さむい adjective
寒かっ 寒い さむかっ adjective
高い たかい adjective
高く 高い たかく adjective
高かっ 高い たかかっ adjective
辛 辛い から adjective
優しく 優しい やさしく adjective
美味しい おいしい adjective
おいしい 美味しい おいしい adjective
美味しかっ 美味しい おいしかっ adjective
暑く 暑い あつく adjective
眠い ねむい adjective
面白く 面白い おもしろく adjective
狭く 狭い せまく adjective
危ない あぶない adjective
楽しい たのしい adjective
忙しい いそがしい adjective
多い おおい adjective
痛い いたい adjective
安く 安い やすく adjective
白く 白い しろく adjective
嬉しかっ 嬉しい うれしかっ adjective
すごい 凄い すごい adjective
好き すき adjectival-noun
静か しずか adjectival-noun
親切 しんせつ adjectival-noun
きれい 綺麗 きれい adjectival-noun
上手 じょうず adjectival-noun
大切 たいせつ adjectival-noun
大丈夫 だいじょうぶ adjectival-noun
げんき 元気 げんき adjectival-noun
# nouns
毎朝 まいあさ noun
パン ぱん noun
水 みず noun
歌 うた noun
先生 せんせい noun
学生 がくせい noun
本 ほん noun
魚 さかな noun
生 なま noun
子供 こども noun
母 はは noun
野菜 やさい noun
友達 ともだち noun
ともだち 友達 ともだち noun
部屋 へや noun
一緒 いっしょ noun
勉強 べんきょう noun
手紙 てがみ noun
宿題 しゅくだい noun
ご飯 ごはん noun
ごはん ご飯 ごはん noun
後 あと noun
散歩 さんぽ noun
先週 せんしゅう noun
電気 でんき noun
傘 かさ noun
まま 儘 まま noun
天気 てんき noun
カレー かれー noun
掃除 そうじ noun
道 みち noun
映画 えいが noun
店 みせ noun
街 まち noun
新幹線 しんかんせん noun
運転手 うんてんしゅ noun
夏休み なつやすみ noun
図書館 としょかん noun
自動車 じどうしゃ noun
工場 こうじょう noun
国際電話 こくさいでんわ noun
携帯電話 けいたいでんわ noun
世界中 せかいじゅう noun
入学試験 にゅうがくしけん noun
大学 だいがく noun
来月 らいげつ noun
日本料理 にほんりょうり noun
健康 けんこう noun
駅 えき noun
近く ちかく noun
郵便局 ゆうびんきょく noun
新聞記者 しんぶんきしゃ noun
食料品 しょくりょうひん noun
売り場 うりば noun
地下 ちか noun
駅前 えきまえ noun
広場 ひろば noun
日本語 にほんご noun
学習 がくしゅう noun
会議室 かいぎしつ noun
予約 よやく noun
お願い おねがい noun
週末 しゅうまつ noun
旅行 りょこう noun
計画 けいかく noun
朝食 ちょうしょく noun
時間 じかん noun
台風 たいふう noun
接近 せっきん noun
中 なか noun
外 そと noun
地下鉄 ちかてつ noun
乗り場 のりば noun
返却 へんきゃく noun
期限 きげん noun
明日 あした noun
あした 明日 あした noun
英語 えいご noun
教科書 きょうかしょ noun
夜間 やかん noun
外出 がいしゅつ noun
禁止 きんし noun
動画 どうが noun
配信 はいしん noun
サービス さーびす noun
解約 かいやく noun
かばん 鞄 かばん noun
きのう 昨日 きのう noun
昨日 きのう noun
今日 きょう noun
いま 今 いま noun
今 いま noun
あめ 雨 あめ noun
雨 あめ noun
ねこ 猫 ねこ noun
猫 ねこ noun
まど 窓 まど noun
窓 まど noun
そば 傍 そば noun
人々 ひとびと noun
人 ひと noun
時々 ときどき noun
電話 でんわ noun
色々 いろいろ adjectival-noun
日々 ひび noun
日 ひ noun
生活 せいかつ noun
様々 さまざま adjectival-noun
国 くに noun
こゝろ 心 こころ noun
山々 やまやま noun
雪 ゆき noun
お金 おかね noun
お腹 おなか noun
少々 しょうしょう adverb
りんご 林檎 りんご noun
二 に numeral
会社 かいしゃ noun
一人 ひとり noun
一度 いちど noun
コーヒー こーひー noun
パソコン ぱそこん noun
レストラン れすとらん noun
ピザ ぴざ noun
スマホ すまほ noun
バッテリー ばってりー noun
インターネット いんたーねっと noun
ニュース にゅーす noun
テレビ てれび noun
ボリューム ぼりゅーむ noun
バス ばす noun
タクシー たくしー noun
ホテル ほてる noun
ロビー ろびー noun
待ち合わせ まちあわせ noun
アイスクリーム あいすくりーむ noun
ｶﾒﾗ カメラ かめら noun
メール めーる noun
メートル めーとる noun
ミルクティー みるくてぃー noun
砂糖 さとう noun
名前 なまえ noun
冬 ふゆ noun
野球 やきゅう noun
授業 じゅぎょう noun
出身 しゅっしん noun
医者 いしゃ noun
為 ため noun
ため 為 ため noun
こと 事 こと noun
ほう 方 ほう noun
夜 よる noun
星 ほし noun
今朝 けさ noun
約束 やくそく noun
風邪 かぜ noun
歌手 かしゅ noun
休み やすみ noun
家 いえ noun
学校 がっこう noun
電車 でんしゃ noun
料理 りょうり noun
兄 あに noun
背 せ noun
全部 ぜんぶ noun
写真 しゃしん noun
午後 ごご noun
大学 だいがく noun
会議 かいぎ noun
犬 いぬ noun
ラーメン らーめん noun
"""
