import request from './request'
import type {
  LoginUserVO,
  UserVO,
  UserAdmin,
  UserLoginRequest,
  UserRegisterRequest,
  UserUpdateMyRequest,
  UserQueryRequest,
  UserUpdateAdminRequest,
  BaseResponse,
  Page,
} from '@/types'

export const userLogin = (data: UserLoginRequest) =>
  request.post<BaseResponse<LoginUserVO>>('/user/login', data)

export const userRegister = (data: UserRegisterRequest) =>
  request.post<BaseResponse<number>>('/user/register', data)

export const userLogout = () =>
  request.post<BaseResponse<boolean>>('/user/logout')

export const getLoginUser = () =>
  request.get<BaseResponse<LoginUserVO>>('/user/get/login')

export const getUserVOById = (id: number) =>
  request.get<BaseResponse<UserVO>>('/user/get/vo', { params: { id } })

export const updateMyUser = (data: UserUpdateMyRequest) =>
  request.post<BaseResponse<boolean>>('/user/update/my', data)

// 管理员接口
export const listUserByPage = (data: UserQueryRequest) =>
  request.post<BaseResponse<Page<UserAdmin>>>('/user/list/page', data)

export const updateUser = (data: UserUpdateAdminRequest) =>
  request.post<BaseResponse<boolean>>('/user/update', data)

export const deleteUser = (id: number) =>
  request.post<BaseResponse<boolean>>('/user/delete', { id })
