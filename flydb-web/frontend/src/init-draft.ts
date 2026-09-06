import { useConfigDraft, type ConfigDocument, type ConvertDocument } from './config-draft'

/** Init owns the defaults. Only its untouched migration path follows the project directory. */
export function useInitDraft(initial: Record<string, string>, directory: () => string,
  template: (directory: string) => Promise<ConfigDocument>, convert: ConvertDocument) {
  let generatedDirectory = '', generatedLocation: string | undefined
  return useConfigDraft(initial, async () => {
    const target = directory()
    const document = await template(target)
    generatedDirectory = target; generatedLocation = document.values['flydb.locations']
    return document
  }, async input => {
    const target = directory()
    let document = await convert(input)
    if (target !== generatedDirectory) {
      const next = await template(target)
      if (document.values['flydb.locations'] === generatedLocation) {
        document = await convert({ content: document.content,
          values: { 'flydb.locations': next.values['flydb.locations']! }, validate: input.validate })
      }
      generatedDirectory = target; generatedLocation = next.values['flydb.locations']
    }
    return document
  })
}
