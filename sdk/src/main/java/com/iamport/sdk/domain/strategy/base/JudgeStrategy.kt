package com.iamport.sdk.domain.strategy.base

import com.iamport.sdk.data.chai.response.UserData
import com.iamport.sdk.data.chai.response.Users
import com.iamport.sdk.data.remote.ApiHelper
import com.iamport.sdk.data.remote.IamportApi
import com.iamport.sdk.data.remote.ResultWrapper
import com.iamport.sdk.data.sdk.PG
import com.iamport.sdk.data.sdk.Payment
import com.iamport.sdk.domain.di.IamportKoinComponent
import com.orhanobut.logger.Logger
import kotlinx.coroutines.Dispatchers
import org.koin.core.component.KoinApiExtension
import org.koin.core.component.inject

@KoinApiExtension
class JudgeStrategy : BaseStrategy(), IamportKoinComponent {

    // 유저 정보 판단 결과 타입
    enum class JudgeKinds {
        CHAI, WEB, EMPTY
    }

    private val iamportApi: IamportApi by inject() // 아임포트 서버 API

    // #1 API imp uid 에 따른 유저정보 가져오기
    private suspend fun apiGetUsers(userCode: String): ResultWrapper<Users> {
        Logger.d("try apiGetUsers")
        return ApiHelper.safeApiCall(Dispatchers.IO) { iamportApi.getUsers(userCode) }
    }

    suspend fun judge(payment: Payment): Triple<JudgeKinds, UserData?, Payment> {

//        * 1. IMP 서버에 유저 정보 요청해서 chai id 얻음
        val userDataList: ArrayList<UserData>? = when (val response = apiGetUsers(payment.userCode)) {
            is ResultWrapper.NetworkError -> {
                failureFinish(payment, msg = "NetworkError ${response.error}")
                null
            }
            is ResultWrapper.GenericError -> {
                failureFinish(payment, msg = "GenericError ${response.code} ${response.error}")
                null
            }

            is ResultWrapper.Success -> {
                response.value.run {
                    if (code == 0) {
                        data
                    } else {
                        failureFinish(payment, msg = msg)
                        null
                    }
                }
            }
        }

        // 유저 PG 정보 아예 없으면 실패처리
        if (userDataList.isNullOrEmpty()) {
            failureFinish(payment, msg = "Not found PG [ ${payment.iamPortRequest.pg} ] and any PG in your info.")
            return Triple(JudgeKinds.EMPTY, null, payment)
        }

        val defUser = findDefaultUserData(userDataList)
        if (defUser == null) {
            failureFinish(payment, msg = "Not found Default PG. All PG empty.")
            return Triple(JudgeKinds.EMPTY, null, payment)
        }

        Logger.d("userDataList :: $userDataList")
        val split = payment.iamPortRequest.pg.split(".")
        val myPg = split[0]
        val user = userDataList.find {
            if (split.size > 1) {
                it.pg_provider?.getPgSting() == myPg && it.pg_id == split[1]
            } else {
                it.pg_provider?.getPgSting() == myPg
            }
        }

        return when (user) {
            null -> defUser.let { getPgTriple(it, replacePG(it.pg_provider!!, payment)) } // user 를 찾지 못하면 디폴트 값 사용

            else -> getPgTriple(user, payment)
        }
    }

    private fun findDefaultUserData(userDataList: ArrayList<UserData>): UserData? {
        return userDataList.find { it.pg_provider != null }
    }

    /**
     * pg 정보 값 가져옴 first : 타입, second : pg유저, third : 결제 요청 데이터
     */
    private fun getPgTriple(user: UserData, payment: Payment): Triple<JudgeKinds, UserData?, Payment> {
        return when (user.pg_provider) {
            PG.chai -> Triple(JudgeKinds.CHAI, user, payment)
            else -> Triple(JudgeKinds.WEB, user, payment)
        }
    }

    /**
     * payment PG 를 default PG 로 수정함
     */
    private fun replacePG(pg: PG, payment: Payment): Payment {
        val iamPortRequest = payment.iamPortRequest.copy(pg = pg.getPgSting())
        return payment.copy(iamPortRequest = iamPortRequest)
    }

}
