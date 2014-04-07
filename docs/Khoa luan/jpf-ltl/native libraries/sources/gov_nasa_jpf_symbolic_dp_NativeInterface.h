/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class gov_nasa_jpf_symbolic_dp_NativeInterface */

#ifndef _Included_gov_nasa_jpf_symbolic_dp_NativeInterface
#define _Included_gov_nasa_jpf_symbolic_dp_NativeInterface
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     gov_nasa_jpf_symbolic_dp_NativeInterface
 * Method:    isSatisfiable
 * Signature: (Ljava/lang/String;)Z
 */
JNIEXPORT jboolean JNICALL Java_gov_nasa_jpf_symbolic_dp_NativeInterface_isSatisfiable
  (JNIEnv *, jclass, jstring);

/*
 * Class:     gov_nasa_jpf_symbolic_dp_NativeInterface
 * Method:    isSubsumed
 * Signature: (Ljava/lang/String;Ljava/lang/String;)Z
 */
JNIEXPORT jboolean JNICALL Java_gov_nasa_jpf_symbolic_dp_NativeInterface_isSubsumed
  (JNIEnv *, jclass, jstring, jstring);

/*
 * Class:     gov_nasa_jpf_symbolic_dp_NativeInterface
 * Method:    isSubsumedInc
 * Signature: (Ljava/lang/String;Ljava/lang/String;I)Z
 */
JNIEXPORT jboolean JNICALL Java_gov_nasa_jpf_symbolic_dp_NativeInterface_isSubsumedInc
  (JNIEnv *, jclass, jstring, jstring, jint);

/*
 * Class:     gov_nasa_jpf_symbolic_dp_NativeInterface
 * Method:    assertFormula
 * Signature: (Ljava/lang/String;I)I
 */
JNIEXPORT jint JNICALL Java_gov_nasa_jpf_symbolic_dp_NativeInterface_assertFormula
  (JNIEnv *, jclass, jstring, jint);

/*
 * Class:     gov_nasa_jpf_symbolic_dp_NativeInterface
 * Method:    getStringRep
 * Signature: (I)Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_gov_nasa_jpf_symbolic_dp_NativeInterface_getStringRep
  (JNIEnv *, jclass, jint);

/*
 * Class:     gov_nasa_jpf_symbolic_dp_NativeInterface
 * Method:    initializeCVCL
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_gov_nasa_jpf_symbolic_dp_NativeInterface_initializeCVCL
  (JNIEnv *, jclass);

/*
 * Class:     gov_nasa_jpf_symbolic_dp_NativeInterface
 * Method:    initializeOmega
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_gov_nasa_jpf_symbolic_dp_NativeInterface_initializeOmega
  (JNIEnv *, jclass);

/*
 * Class:     gov_nasa_jpf_symbolic_dp_NativeInterface
 * Method:    initializeSTP
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_gov_nasa_jpf_symbolic_dp_NativeInterface_initializeSTP
  (JNIEnv *, jclass);

/*
 * Class:     gov_nasa_jpf_symbolic_dp_NativeInterface
 * Method:    initializeYices
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_gov_nasa_jpf_symbolic_dp_NativeInterface_initializeYices
  (JNIEnv *, jclass);

#ifdef __cplusplus
}
#endif
#endif
