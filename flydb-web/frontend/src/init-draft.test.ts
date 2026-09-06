import { expect, it, vi } from 'vitest'
import { useInitDraft } from './init-draft'
import type { ConfigDocument } from './config-draft'

const doc = (location: string, note = '# init template') => ({ content: note + '\n' + location,
  values: { 'flydb.locations': location }, revision: 'draft' })
it('loads the shared template and preserves an untouched draft', async () => {
  const template = vi.fn().mockResolvedValue(doc('/project/db/migration'))
  const convert = vi.fn().mockResolvedValue(doc('/project/db/migration'))
  const draft = useInitDraft({}, () => '/project', template, convert)
  await draft.switchMode('file')
  expect(template).toHaveBeenCalledWith('/project')
  expect(draft.content.value).toContain('# init template')
  expect(draft.dirty.value).toBe(false)
})
it.each([false, true])('relocates only the default path while retaining authored content (custom=%s)', async custom => {
  let directory = '/old'
  const original = doc('/old/db/migration')
  const authored = doc(custom ? '/custom/scripts' : '/old/db/migration', '# my note')
  const relocated = doc('/new/db/migration', '# my note')
  const template = vi.fn().mockResolvedValueOnce(original).mockResolvedValueOnce(doc('/new/db/migration'))
  const convert = vi.fn().mockResolvedValueOnce(original).mockResolvedValueOnce(authored).mockResolvedValue(relocated)
  const draft = useInitDraft({}, () => directory, template, convert)
  await draft.switchMode('file')
  draft.content.value = authored.content
  directory = '/new'
  const result: ConfigDocument = await draft.document()
  expect(result.content).toContain('# my note')
  expect(result.values['flydb.locations']).toBe(custom ? '/custom/scripts' : '/new/db/migration')
  expect(convert).toHaveBeenCalledTimes(custom ? 2 : 3)
  expect(draft.dirty.value).toBe(true)
})
