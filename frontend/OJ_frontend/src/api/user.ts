import request from './request'
import type {
  LoginUserVO,
  UserVO,
  UserLoginRequest,
  UserRegisterRequest,
  UserUpdateMyRequest,
  BaseResponse,
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
