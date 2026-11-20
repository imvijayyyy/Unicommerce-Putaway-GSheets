package com.prozo.putaway
import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
class PutawayAccessibilityService:AccessibilityService(){
 companion object{
  @Volatile private var service:PutawayAccessibilityService?=null
  @Volatile private var queue:List<String> = emptyList()
  @Volatile private var delay=2000L
  @Volatile private var running=false
  @Volatile private var paused=false
  fun begin(rows:List<String>,wait:Long){queue=rows;delay=wait;paused=false;running=true;service?.startRun()}
  fun togglePause(){paused=!paused;if(!paused)service?.schedule(0)}
  fun stopRun(){running=false;paused=false}
 }
 private val handler=Handler(Looper.getMainLooper()); private var pos=0
 override fun onServiceConnected(){service=this}
 override fun onAccessibilityEvent(event:AccessibilityEvent?){}
 override fun onInterrupt(){stopRun()}
 private fun startRun(){pos=0;schedule(1200)}
 fun schedule(wait:Long){handler.postDelayed({
  if(!running||paused)return@postDelayed
  if(pos>=queue.size){running=false;return@postDelayed}
  val n=findEditable(rootInActiveWindow)
  if(n==null){running=false;return@postDelayed}
  val args=Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,queue[pos])}
  val set=n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)
  val enter=n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
  if(!set||!enter){running=false;return@postDelayed}
  pos++;schedule(delay)
 },wait)}
 private fun findEditable(n:AccessibilityNodeInfo?):AccessibilityNodeInfo?{
  if(n==null)return null
  if(n.isEditable&&n.isEnabled&&n.isVisibleToUser)return n
  for(i in 0 until n.childCount){val found=findEditable(n.getChild(i));if(found!=null)return found}
  return null
 }
}
