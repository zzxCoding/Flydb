import { expect, it, vi } from 'vitest'
import { useConfigDraft, type ConfigDocument } from './config-draft'
const original: ConfigDocument = { content: '# keep\r\nflydb.user=old\r\n', values: { 'flydb.user': 'old' }, revision: 'r1' }
it('lazily opens the source and patches form edits into the existing document', async () => {
  const load = vi.fn().mockResolvedValue(original)
  const changed = { ...original, content: original.content.replace('old', 'new'), values: { 'flydb.user': 'new' } }
  const convert = vi.fn().mockResolvedValue(changed)
  const draft = useConfigDraft(original.values, load, convert)
  expect(load).not.toHaveBeenCalled()
  draft.fields.value['flydb.user'] = 'new'
  await draft.switchMode('file')
  expect(convert).toHaveBeenCalledWith({ content: original.content, values: { 'flydb.user': 'new' }, validate: false })
  expect(draft.content.value).toContain('# keep\r\n')
  expect(draft.dirty.value).toBe(true)
  draft.accept(changed)
  expect(draft.dirty.value).toBe(false)
})
it('preserves invalid raw drafts when conversion to a form fails', async () => {
  const convert = vi.fn().mockResolvedValueOnce(original).mockRejectedValueOnce(new Error('syntax'))
  const draft = useConfigDraft(original.values, async () => original, convert)
  await draft.switchMode('file')
  draft.content.value = 'flydb.user=\\uINVALID'
  await expect(draft.switchMode('form')).rejects.toThrow('syntax')
  expect(draft.mode.value).toBe('file')
  expect(draft.content.value).toBe('flydb.user=\\uINVALID')
  expect(draft.dirty.value).toBe(true)
})
it('keeps source-only comments through form round trips and external-file rebasing', async () => {
  const changed = { ...original, content: '# my note\r\n' + original.content }
  const convert = vi.fn().mockResolvedValueOnce(original).mockResolvedValue(changed)
  const draft = useConfigDraft(original.values, async () => original, convert)
  await draft.switchMode('file')
  draft.content.value = changed.content
  await draft.switchMode('form')
  expect(draft.dirty.value).toBe(true)
  draft.rebase({ ...original, content: '# external\r\n' + original.content, revision: 'r2' })
  expect(draft.content.value).toBe(changed.content)
  expect(draft.dirty.value).toBe(true)
  await draft.switchMode('file')
  expect(convert).toHaveBeenLastCalledWith({ content: changed.content, values: {}, validate: false })
  expect(draft.content.value).toBe(changed.content)
})
