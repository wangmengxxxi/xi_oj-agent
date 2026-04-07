import { createRouter, createWebHistory } from 'vue-router'

// 路由元信息类型
declare module 'vue-router' {
  interface RouteMeta {
    // 所需角色：undefined=无需登录，'user'=需登录，'admin'=仅管理员
    requiresRole?: 'user' | 'admin'
    // 是否显示在导航菜单
    hideInMenu?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/questions',
    },
    // 主布局路由组
    {
      path: '/',
      component: () => import('@/layouts/BasicLayout.vue'),
      children: [
        {
          path: 'questions',
          name: 'questions',
          component: () => import('@/views/question/QuestionsView.vue'),
          meta: { requiresRole: 'user' },
        },
        {
          path: 'view/question/:id',
          name: 'viewQuestion',
          component: () => import('@/views/question/ViewQuestionView.vue'),
          meta: { requiresRole: 'user', hideInMenu: true },
        },
        {
          path: 'question_submit',
          name: 'questionSubmit',
          component: () => import('@/views/question/QuestionSubmitView.vue'),
          meta: { requiresRole: 'user' },
        },
        {
          path: 'add/question',
          name: 'addQuestion',
          component: () => import('@/views/question/AddQuestionView.vue'),
          meta: { requiresRole: 'user' },
        },
        {
          path: 'update/question',
          name: 'updateQuestion',
          component: () => import('@/views/question/AddQuestionView.vue'),
          meta: { requiresRole: 'user', hideInMenu: true },
        },
        {
          path: 'manage/question',
          name: 'manageQuestion',
          component: () => import('@/views/question/ManageQuestionView.vue'),
          meta: { requiresRole: 'admin' },
        },
        {
          path: 'manage/user',
          name: 'manageUser',
          component: () => import('@/views/user/ManageUserView.vue'),
          meta: { requiresRole: 'admin' },
        },
        {
          path: 'profile',
          name: 'profile',
          component: () => import('@/views/user/UserProfileView.vue'),
          meta: { requiresRole: 'user', hideInMenu: true },
        },
        {
          path: 'noAuth',
          name: 'noAuth',
          component: () => import('@/views/NoAuthView.vue'),
          meta: { hideInMenu: true },
        },
      ],
    },
    // 用户认证布局路由组
    {
      path: '/user',
      component: () => import('@/layouts/UserLayout.vue'),
      children: [
        {
          path: 'login',
          name: 'userLogin',
          component: () => import('@/views/user/UserLoginView.vue'),
          meta: { hideInMenu: true },
        },
        {
          path: 'register',
          name: 'userRegister',
          component: () => import('@/views/user/UserRegisterView.vue'),
          meta: { hideInMenu: true },
        },
      ],
    },
  ],
})

export default router
