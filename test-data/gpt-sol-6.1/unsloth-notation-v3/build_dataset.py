"""Build notation v3 from the existing structure-v2 corpus, without changing it.

Standard library only; no API calls. Labels are synthetic editorial examples.
"""
import collections
import hashlib
import json
import random
import re
from pathlib import Path

DEST = Path(__file__).resolve().parent
SOURCE = DEST.parent / 'unsloth-structure-v2'
INSTRUCTION = 'Clean up this dictated transcript. Return only the cleaned text.'

# split | category | contrast family | input | output
# Families (including trivial variations and negative contrasts) stay together.
FIXTURES = r"""
train|hyphen|agreement-two|local agreement hyphen two|Local agreement-2
train|hyphen|agreement-two|local agreement dash two|Local agreement-2
train|preserve|agreement-two|local agreement-2|Local agreement-2
train|hyphen|agreement-two|um please open local agreement hyphen two|Please open local agreement-2.
train|hyphen|agreement-two|send local agreement hyphen one no hyphen two to the team|Send local agreement-2 to the team.
train|literal|agreement-two|local agreement two|Local agreement 2
train|hyphen|release-tag|use release hyphen seven as the folder name|Use release-7 as the folder name.
train|hyphen|release-tag|the release-7 folder is empty|The release-7 folder is empty.
train|hyphen|invoice-id|invoice number A hyphen zero zero five is overdue|Invoice number A-005 is overdue.
train|hyphen|gate-id|the gate is B dash twelve|The gate is B-12.
train|hyphen|version-slug|rename the draft to annual report hyphen twenty twenty six|Rename the draft to annual report-2026.
train|hyphen|compound|this is a well hyphen known problem|This is a well-known problem.
train|hyphen|no-space|type north hyphen east as one compound word|Type north-east as one compound word.
train|hyphen|suffix-correction|save it as budget hyphen three no budget hyphen four|Save it as budget-4.
train|hyphen|hyphen-spelling|the model name is ZQ hyphen seventeen|The model name is ZQ-17.
train|literal|literal-hyphen|the word hyphen appears in the instructions|The word "hyphen" appears in the instructions.
train|literal|literal-hyphen|please explain the difference between a hyphen and a dash|Please explain the difference between a hyphen and a dash.
train|literal|literal-dash|i made a dash for the train|I made a dash for the train.
train|literal|literal-dash|add a dash of salt|Add a dash of salt.
train|literal|quote-hyphen|she literally said hyphen two and then stopped|She literally said "hyphen two" and then stopped.
train|literal|quote-hyphen|keep the words hyphen two written out|Keep the words "hyphen two" written out.
train|parentheses|good-brackets|It's good in brackets it's not good|It's good (it's not good).
train|parentheses|good-brackets|it's good open bracket it's not good close bracket|It's good (it's not good).
train|parentheses|good-brackets|It's good (it's not good).|It's good (it's not good).
train|parentheses|aside-at-end|the meeting is confirmed in brackets pending the room booking|The meeting is confirmed (pending the room booking).
train|parentheses|optional-aside|bring a jacket in brackets if it gets cold|Bring a jacket (if it gets cold).
train|parentheses|aside-scope|the proposal open parenthesis the revised one close parenthesis is ready|The proposal (the revised one) is ready.
train|parentheses|number-aside|the attachment in brackets version two|The attachment (version 2)
train|parentheses|date-aside|the launch in brackets june third|The launch (June 3)
train|parentheses|price-aside|the ticket is thirty dollars in brackets tax included|The ticket is $30 (tax included).
train|parentheses|name-aside|Maya in brackets the designer close brackets will join us later|Maya (the designer) will join us later.
train|parentheses|bracket-correction|the room is available in brackets after five no after six|The room is available (after 6).
train|square_brackets|square-status|status open square bracket pending close square bracket|Status [pending]
train|square_brackets|square-insertion|the quoted phrase is we open square brackets the team close square brackets agree|The quoted phrase is "We [the team] agree."
train|square_brackets|square-code|write item open square bracket zero close square bracket|Write item[0].
train|square_brackets|square-aside|the file is missing in square brackets check the archive|The file is missing [check the archive].
train|square_brackets|square-preserve|The status is [pending].|The status is [pending].
train|curly_braces|curly-example|the placeholder is open curly brace username close curly brace|The placeholder is {username}.
train|literal|literal-brackets|the brackets on the shelf are loose|The brackets on the shelf are loose.
train|literal|literal-parenthesis|put the word parenthesis in the glossary|Put the word "parenthesis" in the glossary.
train|literal|literal-in-brackets|the title contains the phrase in brackets|The title contains the phrase "in brackets."
train|literal|bracket-question|what does a square bracket mean in this code|What does a square bracket mean in this code?
train|literal|bracket-purchase|we need two metal brackets for the shelf|We need 2 metal brackets for the shelf.
train|literal|bracket-idiom|her salary falls into a different tax bracket|Her salary falls into a different tax bracket.
train|numerals|counts-team|we have three designers and two engineers|We have 3 designers and 2 engineers.
train|numerals|copies-count|print five copies of the agenda|Print 5 copies of the agenda.
train|numerals|quantities|order six chairs and one table|Order 6 chairs and 1 table.
train|numerals|duration|the upload took seven minutes|The upload took 7 minutes.
train|numerals|decimal-units|the cable is two point five metres long|The cable is 2.5 metres long.
train|numerals|percent|reduce the fee by five percent|Reduce the fee by 5%.
train|numerals|age|the building is eight years old|The building is 8 years old.
train|numerals|reference|see page nine paragraph two|See page 9, paragraph 2.
train|numerals|chapter|open chapter four and read section six|Open chapter 4 and read section 6.
train|numerals|version|we are testing version three point two|We are testing version 3.2.
train|numerals|time|meet me at four fifteen pm|Meet me at 4:15 PM.
train|numerals|time-no-invent|call me at eight|Call me at 8.
train|numerals|temperature|the temperature is minus three degrees celsius|The temperature is -3 degrees Celsius.
train|numerals|math-question|what is six plus seven|What is 6 plus 7?
train|numerals|decimal-preserve|keep the value at one point zero zero|Keep the value at 1.00.
train|numerals|leading-zero|the access code is zero zero two nine|The access code is 0029.
train|numerals|phone|the extension is one zero four|The extension is 104.
train|numerals|measured-range|the expected wait is three to five minutes|The expected wait is 3 to 5 minutes.
train|numerals|ordinal-reference|check the second row and the third column|Check the 2nd row and the 3rd column.
train|numerals|fraction-measured|add one half teaspoon of salt|Add 1/2 teaspoon of salt.
train|numerals|correction-count|we need four copies wait no five copies|We need 5 copies.
train|numerals|negative-vs-hyphen|the balance is minus two dollars|The balance is -$2.
train|hyphen|negative-vs-hyphen|the receipt label is expense hyphen two|The receipt label is expense-2.
train|numerals|compact-list|shopping list two apples four oranges and one pumpkin|Shopping list:\n* 2 apples\n* 4 oranges\n* 1 pumpkin
train|parentheses|list-brackets|packing list three shirts in brackets blue ones close brackets two pairs of socks and one raincoat|Packing list:\n* 3 shirts (blue ones)\n* 2 pairs of socks\n* 1 raincoat
train|email|email-numbers|hi Noor please send two copies of contract hyphen five in brackets the signed version close brackets thanks Dev|Hi Noor,\n\nPlease send 2 copies of contract-5 (the signed version).\n\nThanks,\nDev
train|literal|idiom-one|she is one of a kind|She is one of a kind.
train|literal|idiom-two|give it a second thought|Give it a second thought.
train|literal|idiom-one|let's take it one step at a time|Let's take it one step at a time.
train|literal|idiom-number|we're back to square one|We're back to square one.
train|literal|idiom-number|i'll be there in a second|I'll be there in a second.
train|literal|idiom-two|that's a two way street|That's a two-way street.
train|literal|proper-names|we booked a table at Five Guys|We booked a table at Five Guys.
train|literal|proper-names|she uses a OnePlus phone|She uses a OnePlus phone.
train|literal|proper-names|i'm reading The Three-Body Problem|I'm reading The Three-Body Problem.
train|literal|proper-names|the team is called Formula One|The team is called Formula One.
train|literal|spelled-request|spell out the number three|Spell out the number three.
train|literal|spelled-request|write five in words|Write five in words.
train|literal|quote-numbers|she said the word seven very slowly|She said the word "seven" very slowly.
train|literal|uncertain-suffix|the document is called annual agreement two|The document is called annual agreement 2.
train|preserve|punctuated-numbers|Please review agreement-2 (version 3) by 4:00 PM.|Please review agreement-2 (version 3) by 4:00 PM.
train|numerals|arithmetic-negative|the result is minus two point five|The result is -2.5.
train|hyphen|letter-slug|the tag is alpha hyphen B hyphen three|The tag is alpha-B-3.
train|parentheses|literal-words-in-aside|she agreed in brackets she actually said yes|She agreed (she actually said yes).
train|parentheses|multi-sentence-aside|the plan is approved open parenthesis the date is provisional do not book travel yet close parenthesis|The plan is approved (the date is provisional; do not book travel yet).
train|parentheses|parentheses-question|can we proceed open parenthesis assuming the budget is approved close parenthesis question mark|Can we proceed (assuming the budget is approved)?
train|literal|speak-about-command|the transcript contains open bracket and close bracket as spoken words|The transcript contains "open bracket" and "close bracket" as spoken words.
train|hyphen|punctuation-sequence|the filename is notes hyphen two dot txt|The filename is notes-2.txt.
train|literal|do-not-invent-openers|the file is called notes two dot txt|The file is called notes 2.txt.
validation|hyphen|val-annex|please attach annex hyphen eleven|Please attach annex-11.
validation|hyphen|val-room|the room label is C dash zero nine|The room label is C-09.
validation|hyphen|val-correction|rename it preview hyphen six no preview hyphen eight|Rename it preview-8.
validation|parentheses|val-bracket|it's available in brackets only on weekends|It's available (only on weekends).
validation|parentheses|val-internal|the invoice open brackets the updated copy close brackets is attached|The invoice (the updated copy) is attached.
validation|square_brackets|val-square|the label is approved in square brackets with conditions|The label is approved [with conditions].
validation|curly_braces|val-curly|use open curly bracket project_id close curly bracket as the placeholder|Use {project_id} as the placeholder.
validation|literal|val-metal|those brackets support the radiator|Those brackets support the radiator.
validation|literal|val-dash|he added a dash of pepper|He added a dash of pepper.
validation|literal|val-hyphen-word|the instructions mention the phrase hyphen eleven|The instructions mention the phrase "hyphen eleven."
validation|numerals|val-quantities|we sold eight tickets and reserved two seats|We sold 8 tickets and reserved 2 seats.
validation|numerals|val-distance|the station is four kilometres away|The station is 4 kilometres away.
validation|numerals|val-ref|look at page seven line five|Look at page 7, line 5.
validation|numerals|val-leading-zero|the locker code is zero three zero six|The locker code is 0306.
validation|numerals|val-time|the call starts at six forty am|The call starts at 6:40 AM.
validation|literal|val-idiom|that's one way to look at it|That's one way to look at it.
validation|literal|val-proper|the venue is called Seven Dials|The venue is called Seven Dials.
validation|literal|val-words|keep the number eight written as a word|Keep the number eight written as a word.
validation|parentheses|val-mixed|send diagram hyphen four in brackets not the old copy close brackets to three people|Send diagram-4 (not the old copy) to 3 people.
validation|literal|val-unknown-slug|i named the folder preview eight|I named the folder preview 8.
test|hyphen|test-batch|the batch number is lot hyphen twenty one|The batch number is lot-21.
test|hyphen|test-code|look for label R dash zero zero eight|Look for label R-008.
test|hyphen|test-corrected|use appendix hyphen three wait no appendix hyphen nine|Use appendix-9.
test|parentheses|test-bracket|the room is quiet in brackets unless the window is open|The room is quiet (unless the window is open).
test|parentheses|test-scope|the estimate open parenthesis labour only close parenthesis excludes materials|The estimate (labour only) excludes materials.
test|square_brackets|test-square|write the status in square brackets awaiting payment|Write the status [awaiting payment].
test|curly_braces|test-curly|the template token is open brace customer_id close brace|The template token is {customer_id}.
test|literal|test-literal-bracket|the word brackets is plural|The word "brackets" is plural.
test|literal|test-literal-hyphen|the editor asked whether the title needs a hyphen|The editor asked whether the title needs a hyphen.
test|literal|test-quoted-hyphen|he read out dash zero zero eight from the instructions|He read out "dash zero zero eight" from the instructions.
test|numerals|test-count|there are nine volunteers and four coordinators|There are 9 volunteers and 4 coordinators.
test|numerals|test-unit|the gap is one point five millimetres|The gap is 1.5 millimetres.
test|numerals|test-label|refer to table six item eight|Refer to table 6, item 8.
test|numerals|test-id|my booking code is zero zero six one|My booking code is 0061.
test|numerals|test-time|the gate closes at seven twenty five pm|The gate closes at 7:25 PM.
test|literal|test-idiom|we need to get our second wind|We need to get our second wind.
test|literal|test-proper|the film is called Ocean's Eleven|The film is called Ocean's Eleven.
test|literal|test-request-words|please leave nine spelled out as a word|Please leave nine spelled out as a word.
test|parentheses|test-mixed|review memo hyphen twelve in brackets revised yesterday close brackets before printing six copies|Review memo-12 (revised yesterday) before printing 6 copies.
test|literal|test-unknown|the drawing is named elevation nine|The drawing is named elevation 9.
"""

# Changes to earlier gold labels are deliberately narrow and audited. Do not
# replace arbitrary number words: e.g. "the old one", "one of", names and idioms.
NUMBER_WORDS = dict(one='1', two='2', three='3', four='4', five='5', six='6', seven='7', eight='8', nine='9', ten='10')
COUNT_NOUNS = r'(?:copies|questions|kilograms|litre|guests|AI tools|cartons|loaf|eggs|sleeping bags|centimetres|people|laptop|presentations|microphones|stands|destinations)'


def relabel_v2(text):
    text = re.sub(r'\b(one|two|three|four|five|six|seven|eight|nine|ten)(?=\s+' + COUNT_NOUNS + r'\b)',
                  lambda m: NUMBER_WORDS[m[0].lower()], text, flags=re.I)
    text = re.sub(r'\b(?<=at )(two|five|nine|ten)\b', lambda m: NUMBER_WORDS[m[0]], text)
    text = text.replace('numbers one, two, and three', 'numbers 1, 2, and 3')
    text = text.replace('What is two plus two?', 'What is 2 plus 2?')
    text = text.replace('two-thirds of the flour', '2/3 of the flour')
    return text


def fixtures():
    result = []
    for i, line in enumerate(FIXTURES.strip().splitlines(), 1):
        fields = line.split('|')
        assert len(fields) == 5, (i, fields)
        split, category, family, inp, out = fields
        result.append(dict(split=split, category=category, family='notation-' + family,
                           instruction=INSTRUCTION, input=inp, output=out.replace(r'\n', '\n')))
    return result


def dump(filename, obj):
    (DEST / filename).write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main():
    additions = fixtures()
    source_files = [SOURCE / (s + '.jsonl') for s in ('train', 'validation', 'test')]
    before_hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in source_files}
    old_metadata = json.loads((SOURCE / 'metadata.json').read_text(encoding='utf-8'))
    meta_by_input_hash = {m['sha256']: m for m in old_metadata}
    seen, families, master, metadata, changes = set(), collections.defaultdict(set), [], [], []
    report = {}
    rng = random.Random(20261103)
    for split in ('train', 'validation', 'test'):
        old_rows = [json.loads(s) for s in (SOURCE / (split + '.jsonl')).read_text(encoding='utf-8').splitlines()]
        combined = []
        for row in old_rows:
            inp, old_out = [m['content'] for m in row['messages']]
            out = relabel_v2(old_out)
            if out != old_out:
                changes.append(dict(split=split, input=inp, old_output=old_out, new_output=out,
                                    reason='v3 contextual numerals policy'))
            h = hashlib.sha256(inp.encode()).hexdigest()
            meta = dict(meta_by_input_hash[h], origin='v2', label_revised=out != old_out)
            assert meta['split'] == split
            combined.append((dict(instruction=INSTRUCTION, input=inp, output=out), meta))
        for row in additions:
            if row['split'] == split:
                combined.append(({k: row[k] for k in ('instruction', 'input', 'output')},
                                 dict(split=split, category=row['category'], family=row['family'],
                                      origin='notation-v3', label_revised=False)))
        rng.shuffle(combined)
        exported = []
        for row, meta in combined:
            assert row['input'] not in seen, row['input']
            seen.add(row['input'])
            families[meta['family']].add(split)
            meta.update(index=len(master), sha256=hashlib.sha256(row['input'].encode()).hexdigest())
            master.append(row)
            metadata.append(meta)
            exported.append(dict(messages=[dict(role='user', content=row['input']),
                                           dict(role='assistant', content=row['output'])]))
        path = DEST / (split + '.jsonl')
        path.write_text(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in exported), encoding='utf-8')
        assert [json.loads(s) for s in path.read_text(encoding='utf-8').splitlines()] == exported
        report[split] = dict(total=len(exported), retained=len(old_rows), added=len(exported)-len(old_rows),
                             relabeled=sum(c['split'] == split for c in changes))
    assert all(len(s) == 1 for s in families.values()), 'Cross-split family leakage'
    assert before_hashes == {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in source_files}
    assert len(master) == 3118 + len(additions)
    assert all(set(row) == {'instruction', 'input', 'output'} for row in master)
    assert all(row['instruction'] == INSTRUCTION for row in master)
    # Boundary assertions catch destructive numeric substitution and regressions.
    for s in ['The old one is fine.', 'One of a kind.', 'Studio Six', 'Five Guys', 'two-way street']:
        assert relabel_v2(s) == s
    assert relabel_v2('We need three copies and two questions.') == 'We need 3 copies and 2 questions.'
    dump('master.json', master)
    dump('metadata.json', metadata)
    dump('notation_additions.json', additions)
    dump('label_changes.json', changes)
    report.update(total=len(master), unique_inputs=len(seen), additions=len(additions),
                  relabeled_existing_examples=len(changes),
                  addition_categories=dict(collections.Counter(r['category'] for r in additions)),
                  source_sha256=before_hashes, original_splits_preserved=True, system_messages=0,
                  validation_scope='Structural and consistency checks, not model-quality or independent human label validation.')
    dump('validation_report.json', report)
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
