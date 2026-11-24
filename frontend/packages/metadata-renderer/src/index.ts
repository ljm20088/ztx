/** S0 只定义受控元数据边界，不执行动态脚本或生成任意 HTML。 */
export interface MetadataPageDefinition { doctype:string; version:number; view:'list'|'form' }