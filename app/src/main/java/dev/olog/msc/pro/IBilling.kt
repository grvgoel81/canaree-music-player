package dev.olog.msc.pro

import android.arch.lifecycle.DefaultLifecycleObserver
import android.arch.lifecycle.LifecycleOwner
import android.support.v7.app.AppCompatActivity
import com.android.billingclient.api.*
import dev.olog.msc.BuildConfig
import dev.olog.msc.domain.interactor.prefs.AppPreferencesUseCase
import dev.olog.msc.domain.interactor.prefs.MusicPreferencesUseCase
import dev.olog.msc.utils.k.extension.toast
import dev.olog.msc.utils.k.extension.unsubscribe
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.Observables
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.properties.Delegates

interface IBilling {

    fun isTrial(): Boolean
    fun isPremium(): Boolean
    fun observeIsPremium(): Observable<Boolean>
    fun observeTrialPremiumState(): Observable<BillingState>
    fun purchasePremium()

    data class BillingState(
            val isTrial: Boolean,
            val isBought: Boolean
    )

}

private const val PRO_VERSION_ID = "pro_version"
private val DEFAULT_PREMIUM = BuildConfig.DEBUG
private val DEFAULT_TRIAL = BuildConfig.DEBUG

private val TRIAL_TIME = TimeUnit.HOURS.toMillis(1L)

class BillingImpl @Inject constructor(
        private val activity: AppCompatActivity,
        private val appPrefsUseCase: AppPreferencesUseCase,
        private val musicPreferencesUseCase: MusicPreferencesUseCase

) : IBilling, PurchasesUpdatedListener, DefaultLifecycleObserver {

    private var isConnected = false

    private val premiumPublisher = BehaviorSubject.createDefault(DEFAULT_PREMIUM)
    private val trialPublisher = BehaviorSubject.createDefault(DEFAULT_TRIAL)

    private var setDefaultDisposable: Disposable? = null

    private var isTrialState by Delegates.observable(DEFAULT_TRIAL, { _, _, new ->
        trialPublisher.onNext(new)
        if (!isPremium()){
            setDefault()
        }
    })

    private var isPremiumState by Delegates.observable(DEFAULT_PREMIUM, { _, _, new ->
        premiumPublisher.onNext(new)
        if (!isPremium()){
            setDefault()
        }
    })


    private val billingClient = BillingClient.newBuilder(activity)
            .setListener(this)
            .build()

    private var countDownDisposable : Disposable? = null

    init {
        activity.lifecycle.addObserver(this)
        startConnection { checkPurchases() }

        if (isStillTrial()){
            isTrialState = true
            countDownDisposable = Observable.interval(5, TimeUnit.MINUTES, Schedulers.computation())
                    .map { isStillTrial() }
                    .doOnNext { isTrialState = it }
                    .takeWhile { it }
                    .subscribe({}, Throwable::printStackTrace)
        }
    }

    private fun isStillTrial(): Boolean {
        val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val firstInstallTime = packageInfo.firstInstallTime
        return System.currentTimeMillis() - firstInstallTime < TRIAL_TIME
    }

    override fun onDestroy(owner: LifecycleOwner) {
        if (billingClient.isReady){
            billingClient.endConnection()
        }
        countDownDisposable.unsubscribe()
        setDefaultDisposable.unsubscribe()
    }

    private fun startConnection(func: (() -> Unit)?){
        if (isConnected){
            func?.invoke()
            return
        }

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(responseCode: Int) {
//                println("onBillingSetupFinished with response code:$responseCode")

                when (responseCode){
                    BillingClient.BillingResponse.OK -> isConnected = true
                    BillingClient.BillingResponse.BILLING_UNAVAILABLE -> activity.toast("Play store not found")
                }
                func?.invoke()
            }
            override fun onBillingServiceDisconnected() {
                isConnected = false
            }
        })
    }

    private fun checkPurchases(){
        val purchases = billingClient.queryPurchases(BillingClient.SkuType.INAPP)
        if (purchases.responseCode == BillingClient.BillingResponse.OK){
            isPremiumState = isProBought(purchases.purchasesList)
        }
    }

    override fun onPurchasesUpdated(responseCode: Int, purchases: MutableList<Purchase>?) {
        when (responseCode){
            BillingClient.BillingResponse.OK -> {
                isPremiumState = isProBought(purchases)
            }
//            else -> Log.w("Billing", "billing response code=$responseCode")
        }
    }

    private fun isProBought(purchases: MutableList<Purchase>?): Boolean {
        return purchases?.firstOrNull { it.sku == PRO_VERSION_ID } != null
//        return true
    }

    override fun isTrial(): Boolean = isTrialState

    override fun isPremium(): Boolean = isTrialState || isPremiumState

    override fun observeIsPremium(): Observable<Boolean> {
        return Observables.combineLatest(premiumPublisher, trialPublisher,
                { premium, trial -> premium || trial })
    }

    override fun observeTrialPremiumState(): Observable<IBilling.BillingState> {
        return Observables.combineLatest(premiumPublisher, trialPublisher,
                { premium, trial -> IBilling.BillingState(trial, premium) })
    }

    override fun purchasePremium() {
        startConnection {
            val params = BillingFlowParams.newBuilder()
                    .setSku(PRO_VERSION_ID)
                    .setType(BillingClient.SkuType.INAPP)
                    .build()

            billingClient.launchBillingFlow(activity, params)
        }
    }

    private fun setDefault(){
        setDefaultDisposable.unsubscribe()
        setDefaultDisposable = appPrefsUseCase.setDefault()
                .andThen(musicPreferencesUseCase.setDefault())
                .subscribeOn(Schedulers.io())
                .subscribe({}, Throwable::printStackTrace)
    }
}