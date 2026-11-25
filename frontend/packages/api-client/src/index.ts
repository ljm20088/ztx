export interface ApiError { code:string; message:string; traceId?:string }
export class ApiClientError extends Error {
  constructor(readonly status:number, readonly detail:ApiError) { super(detail.message); this.name='ApiClientError' }
}
/** 统一 API 请求出口；默认携带 HttpOnly 会话 Cookie，租户路由由服务端认证上下文决定。 */
export async function requestJson<T>(input:RequestInfo|URL, init?:RequestInit):Promise<T> {
  const response=await fetch(input,{credentials:'include',...init,headers:{Accept:'application/json',...init?.headers}})
  if(!response.ok) {
    const detail=await response.json().catch(()=>({code:'HTTP_ERROR',message:response.statusText||'请求失败'})) as ApiError
    throw new ApiClientError(response.status,detail)
  }
  return response.json() as Promise<T>
}